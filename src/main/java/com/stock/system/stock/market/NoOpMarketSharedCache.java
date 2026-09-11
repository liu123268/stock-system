package com.stock.system.stock.market;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        name = "stock-system.market.redis.enabled",
        havingValue = "false",
        matchIfMissing = true
)
class NoOpMarketSharedCache implements MarketSharedCache {

    @Override
    public Optional<String> get(String key) {
        return Optional.empty();
    }

    @Override
    public void put(String key, String value, Duration ttl) {
    }

    @Override
    public void delete(String key) {
    }

    @Override
    public MarketCacheLock tryLock(String key, Duration ttl) {
        return MarketCacheLock.unavailable();
    }

    @Override
    public void unlock(String key, MarketCacheLock lock) {
    }
}
