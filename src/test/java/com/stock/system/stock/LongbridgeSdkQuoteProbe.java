package com.stock.system.stock;

import com.longbridge.Config;
import com.longbridge.ErrorKind;
import com.longbridge.OAuth;
import com.longbridge.OAuthBuilder;
import com.longbridge.OpenApiException;
import com.longbridge.quote.QuoteContext;
import com.longbridge.quote.SecurityQuote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The only place where the probe feeds an OAuth token to SDK 4.4.1.
 *
 * Java 4.4.1 exposes {@code OAuthBuilder} but not the newer {@code OAuth.fromToken}
 * helper.  The adapter therefore uses the SDK's documented JSON cache format for one
 * short operation, with an exclusive temporary client-id file that is removed in
 * {@code close()}. It never replaces an existing user's cache file.
 */
final class LongbridgeSdkQuoteProbe {
    private static final String[] SYMBOLS = {"600519.SH", "NVDA.US"};
    private static final long MIN_REMAINING_SECONDS = 600;
    private static final long MAX_REMAINING_SECONDS = 31_536_000;

    private LongbridgeSdkQuoteProbe() {
    }

    static String probe(String clientId, String accessToken, long expiresAt) {
        validateClientId(clientId);
        validateAccessToken(accessToken);
        long remaining = expiresAt - java.time.Instant.now().getEpochSecond();
        if (remaining < MIN_REMAINING_SECONDS || remaining > MAX_REMAINING_SECONDS) {
            throw new LongbridgeLocalAuthorization.SafeFailure("AUTH_EXPIRY_UNUSABLE");
        }

        try (EphemeralTokenFile ignored = EphemeralTokenFile.create(clientId, accessToken, expiresAt);
             OAuth oauth = new OAuthBuilder(clientId)
                     .setCallbackPort(LongbridgeLocalAuthorization.PORT)
                     .build(url -> { throw new LongbridgeLocalAuthorization.SafeFailure("SDK_CACHE_MISS"); })
                     .get(20, TimeUnit.SECONDS);
             Config config = Config.fromOAuth(oauth).disablePrintQuotePackages();
             QuoteContext quoteContext = QuoteContext.create(config)) {
            SecurityQuote[] quotes = quoteContext.getQuote(SYMBOLS)
                    .get(30, TimeUnit.SECONDS);
            if (quotes == null || quotes.length != SYMBOLS.length) {
                throw new LongbridgeLocalAuthorization.SafeFailure("QUOTE_EMPTY");
            }
            Map<String, SecurityQuote> bySymbol = new LinkedHashMap<>();
            for (SecurityQuote quote : quotes) {
                if (quote == null || quote.getSymbol() == null || bySymbol.put(quote.getSymbol(), quote) != null) {
                    throw new LongbridgeLocalAuthorization.SafeFailure("QUOTE_SYMBOL_MISMATCH");
                }
            }
            StringBuilder result = new StringBuilder("LONGBRIDGE_QUOTE_OK");
            for (String symbol : SYMBOLS) {
                SecurityQuote quote = bySymbol.get(symbol);
                if (quote == null) {
                    throw new LongbridgeLocalAuthorization.SafeFailure("QUOTE_SYMBOL_MISMATCH");
                }
                if (quote.getTimestamp() == null) {
                    throw new LongbridgeLocalAuthorization.SafeFailure("QUOTE_TIME_MISSING");
                }
                if (quote.getLastDone() == null) {
                    throw new LongbridgeLocalAuthorization.SafeFailure("QUOTE_LAST_MISSING");
                }
                result.append(" symbol=").append(symbol)
                        .append(" last=").append(quote.getLastDone().toPlainString())
                        .append(" quoteTime=").append(quote.getTimestamp())
                        .append(" tradeStatus=").append(quote.getTradeStatus());
            }
            return result.toString();
        } catch (LongbridgeLocalAuthorization.SafeFailure failure) {
            throw failure;
        } catch (TimeoutException timeout) {
            throw new LongbridgeLocalAuthorization.SafeFailure("SDK_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LongbridgeLocalAuthorization.SafeFailure("SDK_INTERRUPTED");
        } catch (ExecutionException execution) {
            throw safeSdkFailure(execution.getCause());
        } catch (OpenApiException failure) {
            throw safeSdkFailure(failure);
        } catch (IOException failure) {
            throw new LongbridgeLocalAuthorization.SafeFailure("SDK_CACHE_FAILURE");
        } catch (Exception failure) {
            // Do not inspect getMessage(): native errors can contain URLs or responses.
            throw new LongbridgeLocalAuthorization.SafeFailure("SDK_FAILURE");
        }
    }

    private static LongbridgeLocalAuthorization.SafeFailure safeSdkFailure(Throwable failure) {
        if (failure instanceof OpenApiException apiFailure) {
            String reason = apiFailure.getKind() == ErrorKind.OAuth
                    ? "SDK_AUTH_REJECTED" : "QUOTE_API_REJECTED";
            Long code = apiFailure.getCode();
            if (code != null && code >= 0 && code <= 9_999_999) {
                reason += "_CODE_" + code;
            }
            return new LongbridgeLocalAuthorization.SafeFailure(reason);
        }
        return new LongbridgeLocalAuthorization.SafeFailure("SDK_QUOTE_FAILED");
    }

    static Path tokenPathForHome(Path home, String clientId) {
        validateClientId(clientId);
        Path normalizedHome = home.toAbsolutePath().normalize();
        return normalizedHome.resolve(".longbridge").resolve("openapi").resolve("tokens").resolve(clientId);
    }

    static String tokenJson(String clientId, String accessToken, long expiresAt) {
        validateClientId(clientId);
        validateAccessToken(accessToken);
        return "{\"client_id\":" + jsonString(clientId)
                + ",\"access_token\":" + jsonString(accessToken)
                + ",\"refresh_token\":null,\"expires_at\":" + expiresAt + "}";
    }

    private static void validateClientId(String clientId) {
        if (clientId == null || !clientId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new LongbridgeLocalAuthorization.SafeFailure("INVALID_CLIENT_ID");
        }
    }

    private static void validateAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > 4096
                || accessToken.codePoints().anyMatch(Character::isISOControl)) {
            throw new LongbridgeLocalAuthorization.SafeFailure("AUTH_RESPONSE_INVALID");
        }
    }

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(current);
            }
        }
        return result.append('"').toString();
    }

    private static void protectFile(Path path) throws IOException {
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(path);
            Set<AclEntryPermission> permissions = EnumSet.of(
                    AclEntryPermission.READ_DATA,
                    AclEntryPermission.WRITE_DATA,
                    AclEntryPermission.APPEND_DATA,
                    AclEntryPermission.READ_ATTRIBUTES,
                    AclEntryPermission.READ_NAMED_ATTRS,
                    AclEntryPermission.READ_ACL,
                    AclEntryPermission.WRITE_ACL,
                    AclEntryPermission.DELETE,
                    AclEntryPermission.SYNCHRONIZE
            );
            AclEntry.Builder builder = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(permissions);
            acl.setAcl(List.of(builder.build()));
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) {
            posix.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    private static final class EphemeralTokenFile implements AutoCloseable {
        private final Path path;
        private boolean owned;

        private EphemeralTokenFile(Path path) {
            this.path = path;
        }

        static EphemeralTokenFile create(String clientId, String accessToken, long expiresAt) throws IOException {
            String homeValue = System.getenv("USERPROFILE");
            if (homeValue == null || homeValue.isBlank()) {
                homeValue = System.getProperty("user.home");
            }
            Path home = Paths.get(homeValue);
            Path target = tokenPathForHome(home, clientId);
            if (Files.exists(target)) {
                throw new LongbridgeLocalAuthorization.SafeFailure("SDK_CACHE_EXISTS");
            }
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".stock-probe-", ".tmp");
            EphemeralTokenFile result = new EphemeralTokenFile(target);
            boolean moved = false;
            try {
                protectFile(temporary);
                Files.writeString(temporary, tokenJson(clientId, accessToken, expiresAt),
                        StandardCharsets.UTF_8);
                protectFile(temporary);
                try {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target);
                }
                moved = true;
                result.owned = true;
                protectFile(target);
                return result;
            } catch (java.nio.file.FileAlreadyExistsException exists) {
                Files.deleteIfExists(temporary);
                throw new LongbridgeLocalAuthorization.SafeFailure("SDK_CACHE_EXISTS");
            } catch (IOException | RuntimeException failure) {
                if (moved) {
                    Files.deleteIfExists(target);
                } else {
                    Files.deleteIfExists(temporary);
                }
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            if (owned) {
                Files.deleteIfExists(path);
                owned = false;
            }
        }
    }
}
