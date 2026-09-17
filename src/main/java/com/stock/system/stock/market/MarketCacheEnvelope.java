package com.stock.system.stock.market;

/**
 * Redis 行情缓存的最小信封，显式记录数据来源，避免把未知格式的数据当作 Longbridge 行情使用。
 */
record MarketCacheEnvelope<T>(String source, T value) {

    static final String LONGBRIDGE_SOURCE = "LONGBRIDGE_OPENAPI";

    static <T> MarketCacheEnvelope<T> fromLongbridge(T value) {
        return new MarketCacheEnvelope<>(LONGBRIDGE_SOURCE, value);
    }

    boolean hasExpectedSource() {
        return LONGBRIDGE_SOURCE.equals(source);
    }
}
