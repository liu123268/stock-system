package com.stock.system.stock.market;

import com.longbridge.Config;
import com.longbridge.Language;
import com.longbridge.OAuth;
import com.longbridge.OAuthBuilder;
import com.longbridge.fundamental.FundamentalContext;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** HTTP-only native resources. This session never creates a QuoteContext or a websocket. */
final class LongbridgeFinancialSdkSession implements AutoCloseable {
    private final OAuth oauth;
    private final Config config;
    private final FundamentalContext context;

    LongbridgeFinancialSdkSession(OAuth oauth, Config config, FundamentalContext context) {
        this.oauth = oauth;
        this.config = config;
        this.context = context;
    }

    static LongbridgeFinancialSdkSession open(String clientId, Duration timeout) throws Exception {
        LongbridgeSdkSession.validateClientId(clientId);
        OAuth oauth = new OAuthBuilder(clientId).build(url -> {
            throw new IllegalStateException("Existing local OAuth authorization required");
        }).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        Config config = null;
        try {
            config = Config.fromOAuth(oauth).language(Language.ZH_CN).disablePrintQuotePackages();
            return new LongbridgeFinancialSdkSession(oauth, config, FundamentalContext.create(config));
        } catch (Exception | LinkageError failure) {
            closeQuietly(config);
            closeQuietly(oauth);
            throw failure;
        }
    }

    FundamentalContext context() { return context; }
    @Override public void close() {
        closeQuietly(context);
        closeQuietly(config);
        closeQuietly(oauth);
    }
    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception ignored) { /* No authorization or provider details in logs. */ }
    }
}
