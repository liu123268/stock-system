package com.stock.system.stock;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the replacement local authorization boundary, using synthetic codes only. */
class LongbridgeAuthorizationUrlTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void classifiesExchangeFailureWithoutExposingTheExceptionMessage() {
        String sensitive = "synthetic-sensitive-response";
        assertEquals("AUTH_TLS_FAILURE", LongbridgeAgentAuth.safeFailureReason(
                new javax.net.ssl.SSLHandshakeException(sensitive)));
        assertEquals("AUTH_DNS_FAILURE", LongbridgeAgentAuth.safeFailureReason(
                new java.io.IOException(new java.net.UnknownHostException(sensitive))));
        assertEquals("AUTH_RESPONSE_INVALID", LongbridgeAgentAuth.safeFailureReason(
                new IllegalArgumentException(sensitive)));
    }

    @Test
    void permitsMavensNativeAccessOptionWhileRejectingDiagnosticOptions() {
        assertFalse(LongbridgeLocalAuthorization.hasUnsafeMavenOptions(null));
        assertFalse(LongbridgeLocalAuthorization.hasUnsafeMavenOptions(" "));
        assertFalse(LongbridgeLocalAuthorization.hasUnsafeMavenOptions("--enable-native-access=ALL-UNNAMED "));
        assertTrue(LongbridgeLocalAuthorization.hasUnsafeMavenOptions("-Djavax.net.debug=all"));
        assertTrue(LongbridgeLocalAuthorization.hasUnsafeMavenOptions(
                "--enable-native-access=ALL-UNNAMED -Djdk.httpclient.HttpClient.log=all"));
    }

    @Test
    void rejectsCrossSiteSubmissionWithoutConsumingAuthorization() throws Exception {
        try (var local = new LongbridgeLocalAuthorization(0)) {
            var response = post(local, "https://unrelated.example", "csrf=fake&code=fake-auth-code");
            assertEquals(403, response.statusCode());
            assertEquals(403, post(local, "null", "csrf=fake&code=fake-auth-code").statusCode());
            assertFalse(local.submittedCode().isDone());
        }
    }

    @Test
    void requiresCsrfAndAcceptsOnlyOneLocalSubmissionWithoutEchoingCode() throws Exception {
        try (var local = new LongbridgeLocalAuthorization(0)) {
            var page = http.send(HttpRequest.newBuilder(URI.create(local.origin() + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("same-origin", page.headers().firstValue("Referrer-Policy").orElseThrow());
            var matcher = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(page.body());
            assertTrue(matcher.find());
            String csrf = matcher.group(1);
            assertEquals(400, post(local, local.origin(), "csrf=wrong&code=synthetic-auth-code").statusCode());
            assertFalse(local.submittedCode().isDone());
            var accepted = post(local, local.origin(), "csrf=" + csrf + "&code=synthetic-auth-code");
            assertEquals(200, accepted.statusCode());
            assertFalse(accepted.body().contains("synthetic-auth-code"));
            assertEquals("synthetic-auth-code", local.submittedCode().get(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(409, post(local, local.origin(), "csrf=" + csrf + "&code=synthetic-auth-code").statusCode());
        }
    }

    @Test
    void rejectsAmbiguousFormsAndNeverIncludesInputInValidationErrors() {
        for (String raw : new String[]{"csrf=x&code=synthetic-auth-code&code=another-code",
                "csrf=x&code=synthetic-auth-code&extra=x", "csrf=x&code=%zz", "code=synthetic-auth-code"}) {
            RuntimeException error = assertThrows(RuntimeException.class, () -> LongbridgeLocalAuthorization.parseForm(raw));
            assertFalse(error.toString().contains("synthetic-auth-code"));
        }
        var error = assertThrows(LongbridgeLocalAuthorization.SafeFailure.class,
                () -> LongbridgeLocalAuthorization.validateCode("https://example.test/?secret=synthetic-sensitive"));
        assertEquals("INVALID_AUTH_CODE_FORMAT", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void closesOwnedListener() throws Exception {
        String origin;
        try (var local = new LongbridgeLocalAuthorization(0)) { origin = local.origin(); }
        String closedOrigin = origin;
        assertThrows(java.io.IOException.class, () -> http.send(
                HttpRequest.newBuilder(URI.create(closedOrigin + "/")).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.discarding()));
    }

    @Test
    void decodesThePackedAgentAuthCodeFormat() {
        String clientId = "01234567-89ab-cdef-0123-456789abcdef";
        String rawCode = "ap_synthetic-auth-code-123456";
        byte[] header = ("\u0001" + (char) clientId.length() + clientId + rawCode)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String displayed = encodeBase58(header);

        LongbridgeAgentAuth.PackedCode packed = LongbridgeAgentAuth.unpackPackedCode(displayed);

        assertNotNull(packed);
        assertEquals(clientId, packed.clientId());
        assertEquals(rawCode, packed.rawCode());
    }

    @Test
    void tokenCacheJsonUsesTheSdkShapeWithoutARefreshToken() {
        String json = LongbridgeSdkQuoteProbe.tokenJson(
                "01234567-89ab-cdef-0123-456789abcdef", "synthetic-token", 2_000_000_000L);

        assertTrue(json.contains("\"client_id\":\"01234567-89ab-cdef-0123-456789abcdef\""));
        assertTrue(json.contains("\"access_token\":\"synthetic-token\""));
        assertTrue(json.contains("\"refresh_token\":null"));
    }

    private static String encodeBase58(byte[] bytes) {
        final String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        int leadingZeros = 0;
        while (leadingZeros < bytes.length && bytes[leadingZeros] == 0) {
            leadingZeros++;
        }
        BigInteger number = new BigInteger(1, bytes);
        StringBuilder digits = new StringBuilder();
        while (number.signum() > 0) {
            BigInteger[] division = number.divideAndRemainder(BigInteger.valueOf(58));
            digits.append(alphabet.charAt(division[1].intValue()));
            number = division[0];
        }
        digits.append("1".repeat(leadingZeros));
        return digits.reverse().toString();
    }

    private HttpResponse<String> post(LongbridgeLocalAuthorization local, String origin, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(local.origin() + "/code"))
                .header("Origin", origin).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
