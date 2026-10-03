package com.stock.system.stock;

import com.longbridge.OAuth;
import com.longbridge.OAuthBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.TimeUnit;

/** Opt-in local setup only. SDK persists/refreshes tokens; no database or quote connection is opened. */
class LongbridgeOAuthSetupTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "STOCK_LONGBRIDGE_OAUTH_SETUP", matches = "true")
    void authorizesThroughOfficialSdk() {
        String clientId = System.getenv("LONGBRIDGE_CLIENT_ID");
        if (clientId == null || !clientId.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new AssertionError("LONGBRIDGE_OAUTH_FAILED reason=INVALID_CLIENT_ID");
        }
        int callbackPort = Integer.parseInt(System.getenv("STOCK_LONGBRIDGE_OAUTH_CALLBACK_PORT"));
        try (OAuth ignored = new OAuthBuilder(clientId).setCallbackPort(callbackPort).build(url -> {
            // This is the intended consent entry, not an access token or callback URL containing a code.
            System.out.println("LONGBRIDGE_OAUTH_AUTHORIZE url=" + url);
            System.out.println("Open this URL in the browser profile signed into the NEW local account.");
        }).get(600, TimeUnit.SECONDS)) {
            System.out.println("LONGBRIDGE_OAUTH_OK noQuoteConnection=true");
        } catch (Exception | LinkageError failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            String diagnostic = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
            java.util.regex.Matcher osError = java.util.regex.Pattern.compile("os error (\\d+)").matcher(diagnostic);
            String osErrorCode = osError.find() ? osError.group(1) : "unknown";
            String reason = java.util.stream.Stream.of("bind", "permission", "denied", "os error", "proxy",
                            "certificate", "connect", "dns", "discovery", "scope", "invalid", "token", "parse", "status", "request")
                    .filter(diagnostic::contains).collect(java.util.stream.Collectors.joining(","));
            // Class names diagnose SDK startup without exposing provider messages or credentials.
            throw new AssertionError("LONGBRIDGE_OAUTH_FAILED type=" + failure.getClass().getSimpleName()
                    + " causeType=" + cause.getClass().getSimpleName() + " categories=" + reason + " osError=" + osErrorCode);
        }
    }
}
