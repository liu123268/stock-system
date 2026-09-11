package com.stock.system.stock.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "stock-system.market.redis.enabled", havingValue = "true")
@EnableConfigurationProperties(MarketRedisProperties.class)
class RedisMarketSharedCache implements MarketSharedCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisMarketSharedCache.class);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final Duration failureCooldown;
    private final Clock clock;
    private volatile Instant unavailableUntil = Instant.EPOCH;

    @Autowired
    RedisMarketSharedCache(
            StringRedisTemplate redisTemplate,
            MarketRedisProperties properties
    ) {
        this(redisTemplate, properties.getFailureCooldown(), Clock.systemUTC());
    }

    RedisMarketSharedCache(
            StringRedisTemplate redisTemplate,
            Duration failureCooldown,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.failureCooldown = failureCooldown;
        this.clock = clock;
    }

    @Override
    public Optional<String> get(String key) {
        if (isCoolingDown()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (RuntimeException exception) {
            markUnavailable(exception);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        if (isCoolingDown()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (RuntimeException exception) {
            markUnavailable(exception);
        }
    }

    @Override
    public void delete(String key) {
        if (isCoolingDown()) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            markUnavailable(exception);
        }
    }

    @Override
    public MarketCacheLock tryLock(String key, Duration ttl) {
        if (isCoolingDown()) {
            return MarketCacheLock.unavailable();
        }
        String owner = UUID.randomUUID().toString();
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey(key), owner, ttl);
            return Boolean.TRUE.equals(acquired)
                    ? MarketCacheLock.acquired(owner)
                    : MarketCacheLock.contended();
        } catch (RuntimeException exception) {
            markUnavailable(exception);
            return MarketCacheLock.unavailable();
        }
    }

    @Override
    public void unlock(String key, MarketCacheLock lock) {
        if (lock.status() != MarketCacheLock.Status.ACQUIRED || isCoolingDown()) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey(key)), lock.owner());
        } catch (RuntimeException exception) {
            markUnavailable(exception);
        }
    }

    private String lockKey(String key) {
        return key + ":lock";
    }

    private boolean isCoolingDown() {
        return unavailableUntil.isAfter(clock.instant());
    }

    private void markUnavailable(RuntimeException exception) {
        Instant previous = unavailableUntil;
        unavailableUntil = clock.instant().plus(failureCooldown);
        if (!previous.isAfter(clock.instant())) {
            LOGGER.warn(
                    "Redis market cache is unavailable; using in-process cache and controlled upstream fallback for {} (failure type: {})",
                    failureCooldown,
                    exception.getClass().getSimpleName()
            );
        }
    }
}
