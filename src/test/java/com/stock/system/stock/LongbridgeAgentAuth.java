package com.stock.system.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Small, one-shot implementation of Longbridge's documented Agent Auth Code exchange.
 * The code is accepted only over loopback by {@link LongbridgeLocalAuthorization}; this
 * class never prints the code, token, response body, or an exception carrying either.
 */
final class LongbridgeAgentAuth {
    private static final URI TOKEN_ENDPOINT = URI.create("https://openapi.longbridge.com/oauth2/token");
    private static final String AGENT_REDIRECT_URI = "https://open.longbridge.com/connect/done";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_DISPLAYED_CODE_LENGTH = 4096;
    private static final ObjectMapper JSON = new ObjectMapper();

    private LongbridgeAgentAuth() {
    }

    static Token redeem(String displayedCode) {
        List<ExchangeAttempt> attempts = exchangeAttempts(displayedCode);
        if (attempts.isEmpty()) {
            throw new LongbridgeLocalAuthorization.SafeFailure("INVALID_AGENT_AUTH_CODE_FORMAT");
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        for (ExchangeAttempt attempt : attempts) {
            String region = attempt.rawCode().startsWith("us_") ? "us" : "ap";
            String body = form(
                    "grant_type", "authorization_code",
                    "code", attempt.rawCode(),
                    "redirect_uri", AGENT_REDIRECT_URI,
                    "client_id", attempt.clientId()
            );
            HttpRequest request = HttpRequest.newBuilder(TOKEN_ENDPOINT)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("x-dc-region", region)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                byte[] responseBytes = response.body();
                if (responseBytes.length > MAX_RESPONSE_BYTES) {
                    throw new LongbridgeLocalAuthorization.SafeFailure("AUTH_RESPONSE_TOO_LARGE");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    // A rejected candidate does not consume the one-time code; the
                    // official CLI tries the next display encoding when necessary.
                    continue;
                }
                JsonNode document = readJson(responseBytes);
                String accessToken = text(document, "access_token");
                long expiresIn = document.path("expires_in").asLong(3600);
                if (accessToken == null || accessToken.isBlank()) {
                    throw new LongbridgeLocalAuthorization.SafeFailure("AUTH_RESPONSE_INVALID");
                }
                if (expiresIn < 600 || expiresIn > 31_536_000) {
                    throw new LongbridgeLocalAuthorization.SafeFailure("AUTH_EXPIRY_UNUSABLE");
                }
                long expiresAt = Instant.now().getEpochSecond() + expiresIn;
                return new Token(attempt.clientId(), accessToken, expiresAt);
            } catch (LongbridgeLocalAuthorization.SafeFailure failure) {
                throw failure;
            } catch (java.net.http.HttpTimeoutException timeout) {
                throw new LongbridgeLocalAuthorization.SafeFailure("AUTH_NETWORK_TIMEOUT");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LongbridgeLocalAuthorization.SafeFailure("AUTH_INTERRUPTED");
            } catch (IOException | RuntimeException failure) {
                throw new LongbridgeLocalAuthorization.SafeFailure(safeFailureReason(failure));
            }
        }
        throw new LongbridgeLocalAuthorization.SafeFailure("AUTH_CODE_REJECTED");
    }

    static String safeFailureReason(Throwable failure) {
        // Classify by type only: exception messages may include credentials or response bodies.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException) return "AUTH_TLS_FAILURE";
            if (cause instanceof java.net.UnknownHostException) return "AUTH_DNS_FAILURE";
            if (cause instanceof java.net.ConnectException) return "AUTH_CONNECT_FAILURE";
            if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) return "AUTH_RESPONSE_INVALID";
        }
        return failure instanceof IOException ? "AUTH_NETWORK_FAILURE" : "AUTH_RESPONSE_INVALID";
    }

    static PackedCode unpackPackedCode(String displayedCode) {
        String raw = normalize(displayedCode);
        if (raw == null) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = decodeBase58(raw);
        } catch (IllegalArgumentException failure) {
            return null;
        }
        if (bytes.length < 3 || bytes[0] != 0x01) {
            return null;
        }
        int clientIdLength = bytes[1] & 0xff;
        if (clientIdLength < 1 || bytes.length <= 2 + clientIdLength) {
            return null;
        }
        String clientId = new String(bytes, 2, clientIdLength, StandardCharsets.UTF_8);
        String authCode = new String(bytes, 2 + clientIdLength,
                bytes.length - 2 - clientIdLength, StandardCharsets.UTF_8);
        if (!isClientId(clientId)) {
            return null;
        }
        if (!authCode.matches("[A-Za-z0-9._~+/=-]{16,4096}")) {
            return null;
        }
        return new PackedCode(clientId, authCode);
    }

    /**
     * Build the current packed-code attempt, with the legacy display encodings
     * supported by the official CLI as a compatibility fallback. All candidates
     * stay in memory and are submitted only to the official token endpoint.
     */
    private static List<ExchangeAttempt> exchangeAttempts(String displayedCode) {
        PackedCode packed = unpackPackedCode(displayedCode);
        if (packed != null) {
            return List.of(new ExchangeAttempt(packed.clientId(), packed.rawCode()));
        }

        String raw = normalize(displayedCode);
        if (raw == null) {
            return List.of();
        }
        Set<String> candidateCodes = new LinkedHashSet<>();
        try {
            candidateCodes.add(Base64.getEncoder().encodeToString(decodeBase58(raw)));
        } catch (IllegalArgumentException ignored) {
            // Not the legacy Base58 display form.
        }
        try {
            candidateCodes.add(Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(raw)));
        } catch (IllegalArgumentException ignored) {
            // Not the legacy unpadded Base64URL display form.
        }
        try {
            Base64.getDecoder().decode(raw);
            candidateCodes.add(raw);
        } catch (IllegalArgumentException ignored) {
            // Not the original standard Base64 form.
        }

        List<ExchangeAttempt> attempts = new ArrayList<>();
        for (String candidate : candidateCodes) {
            String clientId = clientIdFromStandardBase64Code(candidate);
            if (clientId != null) {
                attempts.add(new ExchangeAttempt(clientId, candidate));
            }
        }
        return attempts;
    }

    private static String clientIdFromStandardBase64Code(String code) {
        byte[] jwtBytes;
        try {
            jwtBytes = Base64.getDecoder().decode(code);
        } catch (IllegalArgumentException failure) {
            return null;
        }
        String jwt = new String(jwtBytes, StandardCharsets.UTF_8);
        String[] parts = jwt.split("\\.", -1);
        if (parts.length < 2) {
            return null;
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException failure) {
            return null;
        }
        try {
            String clientId = text(JSON.readTree(payload), "client_id");
            return isClientId(clientId) ? clientId : null;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    private static String normalize(String displayedCode) {
        if (displayedCode == null) {
            return null;
        }
        String raw = displayedCode.trim();
        if (raw.length() >= 2 && ((raw.startsWith("`") && raw.endsWith("`"))
                || (raw.startsWith("\"") && raw.endsWith("\""))
                || (raw.startsWith("'") && raw.endsWith("'")))) {
            raw = raw.substring(1, raw.length() - 1).trim();
        }
        return raw.length() >= 8 && raw.length() <= MAX_DISPLAYED_CODE_LENGTH ? raw : null;
    }

    private static boolean isClientId(String clientId) {
        return clientId != null
                && clientId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    /** Base58 decoder matching the alphabet used by the official CLI/web Connect page. */
    static byte[] decodeBase58(String value) {
        final String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        byte[] input = value.getBytes(StandardCharsets.US_ASCII);
        byte[] decoded = new byte[input.length];
        int outputStart = decoded.length;
        for (byte current : input) {
            int digit = alphabet.indexOf((char) current);
            if (digit < 0) {
                throw new IllegalArgumentException("invalid base58");
            }
            int carry = digit;
            for (int index = decoded.length - 1; index >= outputStart; index--) {
                carry += (decoded[index] & 0xff) * 58;
                decoded[index] = (byte) carry;
                carry >>>= 8;
            }
            while (carry > 0) {
                if (outputStart == 0) {
                    throw new IllegalArgumentException("base58 overflow");
                }
                decoded[--outputStart] = (byte) carry;
                carry >>>= 8;
            }
        }
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == '1') {
            leadingZeros++;
        }
        byte[] result = new byte[leadingZeros + decoded.length - outputStart];
        System.arraycopy(decoded, outputStart, result, leadingZeros, decoded.length - outputStart);
        return result;
    }

    private static JsonNode readJson(byte[] body) throws IOException {
        if (body.length == 0) {
            throw new LongbridgeLocalAuthorization.SafeFailure("AUTH_RESPONSE_INVALID");
        }
        return JSON.readTree(body);
    }

    private static String text(JsonNode document, String name) {
        JsonNode value = document == null ? null : document.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String form(String... pairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8));
            body.append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    record PackedCode(String clientId, String rawCode) {
    }

    private record ExchangeAttempt(String clientId, String rawCode) {
    }

    record Token(String clientId, String accessToken, long expiresAt) {
    }
}
