package com.stock.system.stock.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CachingLongbridgeMarketGatewayTest {

    private ExecutorService executor;

    @AfterEach
    void closeExecutor() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void sameQuoteKeyHitsSharedCacheInsteadOfCallingUpstreamAgain() {
        LongbridgeJsonRpcClient upstream = mock(LongbridgeJsonRpcClient.class);
        InMemorySharedCache cache = new InMemorySharedCache();
        var quote = new LongbridgeQuote(
                "600519.SH", new BigDecimal("1500.00"), Instant.parse("2026-09-11T01:30:00Z"), "Normal");
        when(upstream.getQuotes(List.of("600519.SH"))).thenReturn(Map.of("600519.SH", quote));
        CachingLongbridgeMarketGateway gateway = gateway(upstream, cache);

        Map<String, LongbridgeQuote> first = gateway.getQuotes(List.of("600519.SH"));
        Map<String, LongbridgeQuote> second = gateway.getQuotes(List.of("600519.SH"));

        assertThat(first).containsEntry("600519.SH", quote);
        assertThat(second).isEqualTo(first);
        assertThat(cache.values).hasSize(1);
        verify(upstream, times(1)).getQuotes(List.of("600519.SH"));
    }

    @Test
    void cacheKeyIncludesEveryCandlestickCondition() {
        LongbridgeJsonRpcClient upstream = mock(LongbridgeJsonRpcClient.class);
        InMemorySharedCache cache = new InMemorySharedCache();
        var bar = new LongbridgeCandlestick(
                Instant.parse("2026-09-10T07:00:00Z"), BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, 100, new BigDecimal("100000"));
        when(upstream.getCandlesticks("600519.SH", CandlestickPeriod.DAY, 100))
                .thenReturn(List.of(bar));
        when(upstream.getCandlesticks("600519.SH", CandlestickPeriod.DAY, 200))
                .thenReturn(List.of(bar, bar));
        CachingLongbridgeMarketGateway gateway = gateway(upstream, cache);

        gateway.getCandlesticks("600519.SH", CandlestickPeriod.DAY, 100);
        gateway.getCandlesticks("600519.SH", CandlestickPeriod.DAY, 200);
        gateway.getCandlesticks("600519.SH", CandlestickPeriod.DAY, 100);

        assertThat(cache.values.keySet())
                .anyMatch(key -> key.endsWith(":day:latest:100"))
                .anyMatch(key -> key.endsWith(":day:latest:200"));
        verify(upstream, times(1)).getCandlesticks("600519.SH", CandlestickPeriod.DAY, 100);
        verify(upstream, times(1)).getCandlesticks("600519.SH", CandlestickPeriod.DAY, 200);
    }

    @Test
    void allGatewayDataTypesUseTheSharedCache() {
        LongbridgeJsonRpcClient upstream = mock(LongbridgeJsonRpcClient.class);
        InMemorySharedCache cache = new InMemorySharedCache();
        LocalDate date = LocalDate.of(2026, 9, 10);
        Instant before = Instant.parse("2026-09-11T00:00:00Z");
        var bar = new LongbridgeCandlestick(
                Instant.parse("2026-09-10T07:00:00Z"), BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, 100, new BigDecimal("100000"));
        var point = new LongbridgeIntradayPoint(
                Instant.parse("2026-09-10T01:30:00Z"), BigDecimal.TEN, BigDecimal.TEN,
                10, new BigDecimal("10000"));
        when(upstream.getCandlesticksBefore("600519.SH", CandlestickPeriod.DAY, 10, before))
                .thenReturn(List.of(bar));
        when(upstream.getCandlesticksOnDate("600519.SH", CandlestickPeriod.MINUTE_1, date))
                .thenReturn(List.of(bar));
        when(upstream.getIntraday("600519.SH")).thenReturn(List.of(point));
        when(upstream.isTradingDay("600519.SH", date)).thenReturn(true);
        CachingLongbridgeMarketGateway gateway = gateway(upstream, cache);

        for (int pass = 0; pass < 2; pass++) {
            gateway.getCandlesticksBefore("600519.SH", CandlestickPeriod.DAY, 10, before);
            gateway.getCandlesticksOnDate("600519.SH", CandlestickPeriod.MINUTE_1, date);
            gateway.getIntraday("600519.SH");
            assertThat(gateway.isTradingDay("600519.SH", date)).isTrue();
        }

        verify(upstream, times(1))
                .getCandlesticksBefore("600519.SH", CandlestickPeriod.DAY, 10, before);
        verify(upstream, times(1))
                .getCandlesticksOnDate("600519.SH", CandlestickPeriod.MINUTE_1, date);
        verify(upstream, times(1)).getIntraday("600519.SH");
        verify(upstream, times(1)).isTradingDay("600519.SH", date);
    }

    @Test
    void concurrentSameKeyRequestsAreCoalescedInCurrentProcess() throws Exception {
        LongbridgeJsonRpcClient upstream = mock(LongbridgeJsonRpcClient.class);
        NoOpMarketSharedCache cache = new NoOpMarketSharedCache();
        CountDownLatch upstreamEntered = new CountDownLatch(1);
        CountDownLatch releaseUpstream = new CountDownLatch(1);
        AtomicInteger upstreamCalls = new AtomicInteger();
        var quote = new LongbridgeQuote(
                "600519.SH", new BigDecimal("1500.00"), Instant.parse("2026-09-11T01:30:00Z"), "Normal");
        when(upstream.getQuotes(List.of("600519.SH"))).thenAnswer(ignored -> {
            upstreamCalls.incrementAndGet();
            upstreamEntered.countDown();
            assertThat(releaseUpstream.await(2, TimeUnit.SECONDS)).isTrue();
            return Map.of("600519.SH", quote);
        });
        CachingLongbridgeMarketGateway gateway = gateway(upstream, cache);
        executor = Executors.newFixedThreadPool(6);

        Future<Map<String, LongbridgeQuote>> first = executor.submit(
                () -> gateway.getQuotes(List.of("600519.SH")));
        assertThat(upstreamEntered.await(2, TimeUnit.SECONDS)).isTrue();
        List<Future<Map<String, LongbridgeQuote>>> joined = java.util.stream.IntStream.range(0, 5)
                .mapToObj(ignored -> executor.submit(() -> gateway.getQuotes(List.of("600519.SH"))))
                .toList();
        releaseUpstream.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS)).containsKey("600519.SH");
        for (Future<Map<String, LongbridgeQuote>> future : joined) {
            assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(first.get());
        }
        assertThat(upstreamCalls).hasValue(1);
    }

    @Test
    void redisLockLetsAnotherApplicationInstanceReuseTheFirstResult() throws Exception {
        LongbridgeJsonRpcClient firstUpstream = mock(LongbridgeJsonRpcClient.class);
        LongbridgeJsonRpcClient secondUpstream = mock(LongbridgeJsonRpcClient.class);
        InMemorySharedCache sharedCache = new InMemorySharedCache();
        CountDownLatch firstUpstreamEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstUpstream = new CountDownLatch(1);
        var quote = new LongbridgeQuote(
                "600519.SH", new BigDecimal("1500.00"), Instant.parse("2026-09-11T01:30:00Z"),
                "Normal", Instant.parse("2026-09-11T01:30:01Z"));
        when(firstUpstream.getQuotes(List.of("600519.SH"))).thenAnswer(ignored -> {
            firstUpstreamEntered.countDown();
            assertThat(releaseFirstUpstream.await(2, TimeUnit.SECONDS)).isTrue();
            return Map.of("600519.SH", quote);
        });
        CachingLongbridgeMarketGateway firstInstance = gateway(firstUpstream, sharedCache);
        CachingLongbridgeMarketGateway secondInstance = gateway(secondUpstream, sharedCache);
        executor = Executors.newFixedThreadPool(2);

        Future<Map<String, LongbridgeQuote>> first = executor.submit(
                () -> firstInstance.getQuotes(List.of("600519.SH")));
        assertThat(firstUpstreamEntered.await(2, TimeUnit.SECONDS)).isTrue();
        Future<Map<String, LongbridgeQuote>> second = executor.submit(
                () -> secondInstance.getQuotes(List.of("600519.SH")));
        releaseFirstUpstream.countDown();

        assertThat(first.get(2, TimeUnit.SECONDS)).containsEntry("600519.SH", quote);
        assertThat(second.get(2, TimeUnit.SECONDS)).containsEntry("600519.SH", quote);
        verify(secondUpstream, times(0)).getQuotes(List.of("600519.SH"));
    }

    @Test
    void unavailableSharedCacheFallsBackToExistingUpstreamLayer() {
        LongbridgeJsonRpcClient upstream = mock(LongbridgeJsonRpcClient.class);
        var quote = new LongbridgeQuote(
                "600519.SH", new BigDecimal("1500.00"), Instant.parse("2026-09-11T01:30:00Z"), "Normal");
        when(upstream.getQuotes(List.of("600519.SH"))).thenReturn(Map.of("600519.SH", quote));

        Map<String, LongbridgeQuote> result = gateway(upstream, new NoOpMarketSharedCache())
                .getQuotes(List.of("600519.SH"));

        assertThat(result).containsEntry("600519.SH", quote);
        verify(upstream).getQuotes(List.of("600519.SH"));
    }

    @Test
    void expiredEntryIsLoadedAgain() {
        LongbridgeJsonRpcClient upstream = mock(LongbridgeJsonRpcClient.class);
        InMemorySharedCache cache = new InMemorySharedCache();
        var first = new LongbridgeQuote(
                "600519.SH", new BigDecimal("1500.00"), Instant.parse("2026-09-11T01:30:00Z"), "Normal");
        var second = new LongbridgeQuote(
                "600519.SH", new BigDecimal("1501.00"), Instant.parse("2026-09-11T01:31:00Z"), "Normal");
        when(upstream.getQuotes(List.of("600519.SH")))
                .thenReturn(Map.of("600519.SH", first), Map.of("600519.SH", second));
        CachingLongbridgeMarketGateway gateway = gateway(upstream, cache);

        assertThat(gateway.getQuotes(List.of("600519.SH")).get("600519.SH").lastDone())
                .isEqualByComparingTo("1500.00");
        cache.expireAll();
        assertThat(gateway.getQuotes(List.of("600519.SH")).get("600519.SH").lastDone())
                .isEqualByComparingTo("1501.00");
        verify(upstream, times(2)).getQuotes(List.of("600519.SH"));
    }

    @Test
    void currentAggregatePeriodUsesShortTtlEvenThroughHistoricalApi() {
        Instant now = Instant.parse("2026-09-15T02:00:00Z");
        Instant directApiBefore = Instant.parse("2026-09-15T02:00:01Z");

        for (CandlestickPeriod period : List.of(
                CandlestickPeriod.WEEK, CandlestickPeriod.MONTH, CandlestickPeriod.YEAR)) {
            assertThat(CachingLongbridgeMarketGateway.historicalCandlestickTtl(
                    period, directApiBefore, now, ZoneId.of("Asia/Shanghai"),
                    Duration.ofHours(12), Duration.ofMinutes(1)))
                    .isEqualTo(Duration.ofMinutes(1));
        }
    }

    @Test
    void batchBeforeCurrentAggregatePeriodKeepsLongHistoricalTtl() {
        Instant now = Instant.parse("2026-09-15T02:00:00Z");
        Instant beforeCurrentMonth = Instant.parse("2026-08-31T16:00:00Z");

        assertThat(CachingLongbridgeMarketGateway.historicalCandlestickTtl(
                CandlestickPeriod.MONTH, beforeCurrentMonth, now, ZoneId.of("Asia/Shanghai"),
                Duration.ofHours(12), Duration.ofMinutes(1)))
                .isEqualTo(Duration.ofHours(12));
    }

    private CachingLongbridgeMarketGateway gateway(
            LongbridgeJsonRpcClient upstream,
            MarketSharedCache cache
    ) {
        MarketRedisProperties properties = new MarketRedisProperties();
        properties.setEnabled(true);
        properties.setTtlJitterPercent(0);
        properties.setLockWait(Duration.ofMillis(200));
        return new CachingLongbridgeMarketGateway(
                upstream, cache, new ObjectMapper().findAndRegisterModules(), properties);
    }

    private static class InMemorySharedCache implements MarketSharedCache {

        private final Map<String, Entry> values = new ConcurrentHashMap<>();
        private final Map<String, String> locks = new ConcurrentHashMap<>();

        @Override
        public Optional<String> get(String key) {
            Entry entry = values.get(key);
            if (entry == null || !entry.expiresAt().isAfter(Instant.now())) {
                values.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry.value());
        }

        @Override
        public void put(String key, String value, Duration ttl) {
            values.put(key, new Entry(value, Instant.now().plus(ttl)));
        }

        @Override
        public void delete(String key) {
            values.remove(key);
        }

        @Override
        public MarketCacheLock tryLock(String key, Duration ttl) {
            String owner = java.util.UUID.randomUUID().toString();
            return locks.putIfAbsent(key, owner) == null
                    ? MarketCacheLock.acquired(owner) : MarketCacheLock.contended();
        }

        @Override
        public void unlock(String key, MarketCacheLock lock) {
            if (lock.status() == MarketCacheLock.Status.ACQUIRED) {
                locks.remove(key, lock.owner());
            }
        }

        void expireAll() {
            values.clear();
        }

        private record Entry(String value, Instant expiresAt) {
        }
    }
}
