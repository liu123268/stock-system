package com.stock.system.stock.market;

import com.longbridge.Config;
import com.longbridge.OAuth;
import com.longbridge.OAuthBuilder;
import com.longbridge.quote.QuoteContext;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Owns SDK native resources. Interactive consent belongs to the separate local setup command. */
final class LongbridgeSdkSession implements AutoCloseable {
    private final OAuth oauth;
    private final Config config;
    private final QuoteContext context;

    LongbridgeSdkSession(OAuth oauth, Config config, QuoteContext context) {
        this.oauth = oauth;
        this.config = config;
        this.context = context;
    }

    static LongbridgeSdkSession open(String clientId, Duration timeout) throws Exception {
        validateClientId(clientId);
        OAuth oauth = new OAuthBuilder(clientId).build(url -> {
            // No URL, authorization code or provider exception goes into application logs.
            throw new IllegalStateException("Run scripts/authorize-longbridge.cmd first");
        }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        Config config = null;
        try {
            config = Config.fromOAuth(oauth).disablePrintQuotePackages();
            return new LongbridgeSdkSession(oauth, config, QuoteContext.create(config));
        } catch (Exception | LinkageError failure) {
            if (config != null) closeQuietly(config);
            closeQuietly(oauth);
            throw failure;
        }
    }

    static void validateClientId(String clientId) {
        if (clientId == null || !clientId.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Configure a local OAuth client_id using scripts/authorize-longbridge.cmd");
        }
        UUID.fromString(clientId);
    }

    QuoteContext context() {
        return context;
    }

    @Override
    public void close() {
        closeQuietly(context);
        closeQuietly(config);
        closeQuietly(oauth);
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); }
        catch (Exception ignored) { /* Never print upstream error details. */ }
    }
}
