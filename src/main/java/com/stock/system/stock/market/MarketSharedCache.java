package com.stock.system.stock.market;

import java.time.Duration;
import java.util.Optional;

interface MarketSharedCache {

    Optional<String> get(String key);

    void put(String key, String value, Duration ttl);

    void delete(String key);

    MarketCacheLock tryLock(String key, Duration ttl);

    void unlock(String key, MarketCacheLock lock);
}
