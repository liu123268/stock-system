package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisMarketSharedCacheTest {

    @Test
    void connectionFailureStartsCooldownAndSubsequentReadsFailFast() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.get("market:key"))
                .thenThrow(new RedisConnectionFailureException("test connection failure"));
        RedisMarketSharedCache cache = new RedisMarketSharedCache(
                template,
                Duration.ofSeconds(30),
                Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC)
        );

        assertThat(cache.get("market:key")).isEmpty();
        assertThat(cache.get("market:key")).isEmpty();

        verify(values, times(1)).get("market:key");
    }

    @Test
    void distributedLockIsReleasedOnlyWithItsOwnerToken() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("market:key:lock"), any(String.class), eq(Duration.ofSeconds(8))))
                .thenReturn(true);
        when(template.execute(any(RedisScript.class), eq(List.of("market:key:lock")), any()))
                .thenReturn(1L);
        RedisMarketSharedCache cache = new RedisMarketSharedCache(
                template, Duration.ofSeconds(30), Clock.systemUTC());

        MarketCacheLock lock = cache.tryLock("market:key", Duration.ofSeconds(8));
        cache.unlock("market:key", lock);

        assertThat(lock.status()).isEqualTo(MarketCacheLock.Status.ACQUIRED);
        assertThat(lock.owner()).isNotBlank();
        ArgumentCaptor<Object> owner = ArgumentCaptor.forClass(Object.class);
        verify(template).execute(
                any(RedisScript.class), eq(List.of("market:key:lock")), owner.capture());
        assertThat(owner.getValue()).isEqualTo(lock.owner());
    }
}
