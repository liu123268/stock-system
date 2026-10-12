package com.stock.system.stock.market;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisMarketSharedCacheIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_TEST", matches = "true")
    void competingApplicationInstancesShareTheFirstResultThroughARealRedisLock() throws Exception {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"));
        LettuceConnectionFactory firstConnectionFactory = connectionFactory(host, port);
        LettuceConnectionFactory secondConnectionFactory = connectionFactory(host, port);
        StringRedisTemplate firstTemplate = template(firstConnectionFactory);
        StringRedisTemplate secondTemplate = template(secondConnectionFactory);
        String prefix = "stock-system:test:market:" + UUID.randomUUID();
        String quoteKey = MarketCacheKeys.quotes(prefix, List.of("600519.SH"));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            MarketRedisProperties properties = new MarketRedisProperties();
            properties.setEnabled(true);
            properties.setKeyPrefix(prefix);
            properties.setTtlJitterPercent(0);
            properties.setLockWait(Duration.ofSeconds(2));
            RedisMarketSharedCache firstCache = new RedisMarketSharedCache(
                    firstTemplate, Duration.ZERO, java.time.Clock.systemUTC());
            RedisMarketSharedCache secondCache = new RedisMarketSharedCache(
                    secondTemplate, Duration.ZERO, java.time.Clock.systemUTC());
            LongbridgeJsonRpcClient firstUpstream = mock(LongbridgeJsonRpcClient.class);
            LongbridgeJsonRpcClient secondUpstream = mock(LongbridgeJsonRpcClient.class);
            CountDownLatch firstUpstreamEntered = new CountDownLatch(1);
            CountDownLatch releaseFirstUpstream = new CountDownLatch(1);
            CountDownLatch secondRequestStarted = new CountDownLatch(1);
            Instant retrievedAt = Instant.parse("2026-09-18T01:30:01Z");
            var quote = new LongbridgeQuote(
                    "600519.SH", new BigDecimal("1257.20"),
                    Instant.parse("2026-09-18T01:30:00Z"), "Normal", retrievedAt);
            when(firstUpstream.getQuotes(List.of("600519.SH"))).thenAnswer(ignored -> {
                firstUpstreamEntered.countDown();
                assertThat(releaseFirstUpstream.await(2, TimeUnit.SECONDS)).isTrue();
                return Map.of("600519.SH", quote);
            });
            ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
            CachingLongbridgeMarketGateway firstGateway = new CachingLongbridgeMarketGateway(
                    firstUpstream, firstCache, objectMapper, properties);
            CachingLongbridgeMarketGateway secondGateway = new CachingLongbridgeMarketGateway(
                    secondUpstream, secondCache, objectMapper, properties);

            Future<Map<String, LongbridgeQuote>> firstResult = executor.submit(
                    () -> firstGateway.getQuotes(List.of("600519.SH")));
            assertThat(firstUpstreamEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Future<Map<String, LongbridgeQuote>> secondResult = executor.submit(() -> {
                secondRequestStarted.countDown();
                return secondGateway.getQuotes(List.of("600519.SH"));
            });
            assertThat(secondRequestStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(120);
            assertThat(secondResult.isDone()).isFalse();
            releaseFirstUpstream.countDown();

            assertThat(firstResult.get(2, TimeUnit.SECONDS).get("600519.SH").retrievedAt())
                    .isEqualTo(retrievedAt);
            assertThat(secondResult.get(2, TimeUnit.SECONDS).get("600519.SH").retrievedAt())
                    .isEqualTo(retrievedAt);
            verify(firstUpstream, times(1)).getQuotes(List.of("600519.SH"));
            verify(secondUpstream, times(0)).getQuotes(List.of("600519.SH"));
            assertThat(firstTemplate.hasKey(quoteKey + ":lock")).isFalse();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            firstTemplate.delete(quoteKey);
            firstTemplate.delete(quoteKey + ":lock");
            firstConnectionFactory.destroy();
            secondConnectionFactory.destroy();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_INTEGRATION_TEST", matches = "true")
    void writesReadsExpiresAndCleansAnIsolatedRealRedisKey() throws InterruptedException {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6380"));
        LettuceConnectionFactory connectionFactory = connectionFactory(host, port);
        StringRedisTemplate template = template(connectionFactory);
        String prefix = "stock-system:test:market:" + UUID.randomUUID();
        String key = prefix + ":ttl";
        RedisMarketSharedCache cache = new RedisMarketSharedCache(
                template, Duration.ZERO, java.time.Clock.systemUTC());

        try {
            cache.put(key, "{\"price\":10}", Duration.ofMillis(150));
            assertThat(cache.get(key)).contains("{\"price\":10}");

            MarketCacheLock lock = cache.tryLock(key, Duration.ofSeconds(2));
            assertThat(lock.status()).isEqualTo(MarketCacheLock.Status.ACQUIRED);
            cache.unlock(key, lock);

            Thread.sleep(250);
            assertThat(cache.get(key)).isEmpty();

            MarketRedisProperties properties = new MarketRedisProperties();
            properties.setEnabled(true);
            properties.setKeyPrefix(prefix);
            properties.setTtlJitterPercent(0);
            LongbridgeJsonRpcClient firstUpstream = mock(LongbridgeJsonRpcClient.class);
            LongbridgeJsonRpcClient secondUpstream = mock(LongbridgeJsonRpcClient.class);
            Instant retrievedAt = Instant.parse("2026-09-11T01:30:01Z");
            var quote = new LongbridgeQuote(
                    "600519.SH", new BigDecimal("1500.00"),
                    Instant.parse("2026-09-11T01:30:00Z"), "Normal", retrievedAt);
            when(firstUpstream.getQuotes(List.of("600519.SH")))
                    .thenReturn(Map.of("600519.SH", quote));
            ObjectMapper objectMapper = tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
            CachingLongbridgeMarketGateway firstGateway = new CachingLongbridgeMarketGateway(
                    firstUpstream, cache, objectMapper, properties);
            CachingLongbridgeMarketGateway secondGateway = new CachingLongbridgeMarketGateway(
                    secondUpstream, cache, objectMapper, properties);

            firstGateway.getQuotes(List.of("600519.SH"));
            Map<String, LongbridgeQuote> sharedResult =
                    secondGateway.getQuotes(List.of("600519.SH"));

            assertThat(sharedResult.get("600519.SH").retrievedAt()).isEqualTo(retrievedAt);
            verify(firstUpstream, times(1)).getQuotes(List.of("600519.SH"));
            verify(secondUpstream, times(0)).getQuotes(List.of("600519.SH"));
            String quoteKey = MarketCacheKeys.quotes(prefix, List.of("600519.SH"));
            assertThat(template.opsForValue().get(quoteKey))
                    .contains("\"source\":\"LONGBRIDGE_OPENAPI\"")
                    .contains("retrievedAt")
                    .doesNotContain("APP_SECRET", "ACCESS_TOKEN", "token");
        } finally {
            template.delete(key);
            template.delete(key + ":lock");
            template.delete(MarketCacheKeys.quotes(prefix, List.of("600519.SH")));
            template.delete(MarketCacheKeys.quotes(prefix, List.of("600519.SH")) + ":lock");
            connectionFactory.destroy();
        }
    }

    private LettuceConnectionFactory connectionFactory(String host, int port) {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        return connectionFactory;
    }

    private StringRedisTemplate template(LettuceConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }
}
