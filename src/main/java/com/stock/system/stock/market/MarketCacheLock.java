package com.stock.system.stock.market;

record MarketCacheLock(Status status, String owner) {

    enum Status {
        ACQUIRED,
        CONTENDED,
        UNAVAILABLE
    }

    static MarketCacheLock acquired(String owner) {
        return new MarketCacheLock(Status.ACQUIRED, owner);
    }

    static MarketCacheLock contended() {
        return new MarketCacheLock(Status.CONTENDED, null);
    }

    static MarketCacheLock unavailable() {
        return new MarketCacheLock(Status.UNAVAILABLE, null);
    }
}
