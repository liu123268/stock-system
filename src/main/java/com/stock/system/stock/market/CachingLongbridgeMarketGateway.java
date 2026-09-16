package com.stock.system.stock.market;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Primary
@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "longbridge")
@EnableConfigurationProperties(MarketRedisProperties.class)
class CachingLongbridgeMarketGateway implements LongbridgeQuoteGateway,
        LongbridgeCandlestickGateway, LongbridgeIntradayGateway, LongbridgeTradingDayGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(CachingLongbridgeMarketGateway.class);
    private static final Duration CONTENDED_POLL_INTERVAL = Duration.ofMillis(40);

    private final LongbridgeJsonRpcClient upstream;
    private final MarketSharedCache cache;
    private final ObjectMapper objectMapper;
    private final MarketRedisProperties properties;
    private final Map<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();

    CachingLongbridgeMarketGateway(
            LongbridgeJsonRpcClient upstream,
            MarketSharedCache cache,
            ObjectMapper objectMapper,
            MarketRedisProperties properties
    ) {
        this.upstream = upstream;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Map<String, LongbridgeQuote> getQuotes(List<String> symbols) {
        JavaType type = objectMapper.getTypeFactory().constructMapType(
                Map.class, String.class, LongbridgeQuote.class);
        String key = MarketCacheKeys.quotes(properties.getKeyPrefix(), symbols);
        return getOrLoad(key, type, properties.getQuoteTtl(), () -> upstream.getQuotes(symbols));
    }

    @Override
    public List<LongbridgeCandlestick> getCandlesticks(
            String symbol,
            CandlestickPeriod period,
            int count
    ) {
        JavaType type = listType(LongbridgeCandlestick.class);
        String key = MarketCacheKeys.latestCandlesticks(
                properties.getKeyPrefix(), symbol, period, count);
        return getOrLoad(key, type, properties.getCandlestickTtl(),
                () -> upstream.getCandlesticks(symbol, period, count));
    }

    @Override
    public List<LongbridgeCandlestick> getCandlesticksBefore(
            String symbol,
            CandlestickPeriod period,
            int count,
            Instant before
    ) {
        String key = MarketCacheKeys.candlesticksBefore(
                properties.getKeyPrefix(), symbol, period, count, before);
        Duration ttl = historicalCandlestickTtl(
                period, before, Instant.now(), marketZone(symbol),
                properties.getHistoricalTtl(), properties.getCandlestickTtl());
        return getOrLoad(key, listType(LongbridgeCandlestick.class), ttl,
                () -> upstream.getCandlesticksBefore(symbol, period, count, before));
    }

    @Override
    public List<LongbridgeCandlestick> getCandlesticksOnDate(
            String symbol,
            CandlestickPeriod period,
            LocalDate date
    ) {
        String key = MarketCacheKeys.candlesticksOnDate(
                properties.getKeyPrefix(), symbol, period, date);
        return getOrLoad(key, listType(LongbridgeCandlestick.class), properties.getHistoricalTtl(),
                () -> upstream.getCandlesticksOnDate(symbol, period, date));
    }

    @Override
    public List<LongbridgeIntradayPoint> getIntraday(String symbol) {
        LocalDate marketDate = LocalDate.now(marketZone(symbol));
        String key = MarketCacheKeys.intraday(properties.getKeyPrefix(), symbol, marketDate);
        return getOrLoad(key, listType(LongbridgeIntradayPoint.class), properties.getQuoteTtl(),
                () -> upstream.getIntraday(symbol));
    }

    @Override
    public boolean isTradingDay(String symbol, LocalDate date) {
        String key = MarketCacheKeys.tradingDay(properties.getKeyPrefix(), symbol, date);
        return getOrLoad(key, objectMapper.constructType(Boolean.class),
                properties.getTradingDayTtl(), () -> upstream.isTradingDay(symbol, date));
    }

    private <T> T getOrLoad(
            String key,
            JavaType type,
            Duration baseTtl,
            Supplier<T> loader
    ) {
        Optional<T> cached = read(key, type);
        if (cached.isPresent()) {
            LOGGER.debug("Market cache hit: {}", key);
            return cached.get();
        }
        LOGGER.debug("Market cache miss: {}", key);
        return coalesce(key, () -> loadAfterMiss(key, type, baseTtl, loader));
    }

    private <T> T loadAfterMiss(
            String key,
            JavaType type,
            Duration baseTtl,
            Supplier<T> loader
    ) {
        MarketCacheLock lock = cache.tryLock(key, properties.getLockTtl());
        if (lock.status() == MarketCacheLock.Status.CONTENDED) {
            Optional<T> valueFromOtherInstance = awaitOtherInstance(key, type);
            if (valueFromOtherInstance.isPresent()) {
                LOGGER.debug("Market cache request joined across instances: {}", key);
                return valueFromOtherInstance.get();
            }
            LOGGER.warn("Timed out waiting for Redis market-cache lock; using controlled fallback: {}", key);
        }
        if (lock.status() == MarketCacheLock.Status.ACQUIRED) {
            Optional<T> doubleChecked = read(key, type);
            if (doubleChecked.isPresent()) {
                cache.unlock(key, lock);
                return doubleChecked.get();
            }
        }
        try {
            T loaded = loader.get();
            write(key, loaded, baseTtl);
            LOGGER.debug("Market cache loaded from upstream: {}", key);
            return loaded;
        } finally {
            cache.unlock(key, lock);
        }
    }

    private <T> Optional<T> awaitOtherInstance(String key, JavaType type) {
        long deadline = System.nanoTime() + properties.getLockWait().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(CONTENDED_POLL_INTERVAL.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            Optional<T> cached = read(key, type);
            if (cached.isPresent()) {
                return cached;
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private <T> T coalesce(String key, Supplier<T> loader) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            LOGGER.debug("Market cache request joined in current process: {}", key);
            try {
                return (T) existing.join();
            } catch (CompletionException exception) {
                throw propagate(exception.getCause());
            }
        }
        try {
            T value = loader.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException exception) {
            mine.completeExceptionally(exception);
            throw exception;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private RuntimeException propagate(Throwable throwable) {
        return throwable instanceof RuntimeException runtimeException
                ? runtimeException : new IllegalStateException(throwable);
    }

    private <T> Optional<T> read(String key, JavaType type) {
        Optional<String> json = cache.get(key);
        if (json.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json.get(), type));
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Deleted an unreadable market cache value: {}", key);
            cache.delete(key);
            return Optional.empty();
        }
    }

    private void write(String key, Object value, Duration baseTtl) {
        if (!shouldCache(value)) {
            LOGGER.debug("Skipped an empty market cache value: {}", key);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            cache.put(key, json, MarketCacheTtl.withJitter(
                    baseTtl, properties.getTtlJitterPercent()));
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Skipped an unserializable market cache value: {}", key);
        }
    }

    private boolean shouldCache(Object value) {
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return value != null;
    }

    private JavaType listType(Class<?> itemType) {
        return objectMapper.getTypeFactory().constructCollectionType(List.class, itemType);
    }

    static Duration historicalCandlestickTtl(
            CandlestickPeriod period,
            Instant before,
            Instant now,
            ZoneId marketZone,
            Duration historicalTtl,
            Duration updatingTtl
    ) {
        Instant currentPeriodStart = currentPeriodStart(period, now.atZone(marketZone)).toInstant();
        // before 是排他的上界。只有它不晚于当前周期起点时，结果才不可能包含仍会变化的当前 K 线。
        return before.isAfter(currentPeriodStart) ? updatingTtl : historicalTtl;
    }

    private static ZonedDateTime currentPeriodStart(CandlestickPeriod period, ZonedDateTime now) {
        return switch (period) {
            case MINUTE_1 -> now.truncatedTo(ChronoUnit.MINUTES);
            case MINUTE_5 -> minutePeriodStart(now, 5);
            case MINUTE_15 -> minutePeriodStart(now, 15);
            case MINUTE_30 -> minutePeriodStart(now, 30);
            case HOUR_1 -> now.truncatedTo(ChronoUnit.HOURS);
            case DAY -> now.toLocalDate().atStartOfDay(now.getZone());
            case WEEK -> now.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(now.getZone());
            case MONTH -> now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.getZone());
            case YEAR -> now.toLocalDate().withDayOfYear(1).atStartOfDay(now.getZone());
        };
    }

    private static ZonedDateTime minutePeriodStart(ZonedDateTime now, int periodMinutes) {
        return now.withMinute(now.getMinute() - now.getMinute() % periodMinutes)
                .withSecond(0)
                .withNano(0);
    }

    private ZoneId marketZone(String symbol) {
        String normalized = symbol == null ? "" : symbol.trim().toUpperCase();
        if (normalized.endsWith(".HK")) {
            return ZoneId.of("Asia/Hong_Kong");
        }
        if (normalized.endsWith(".US")) {
            // sidecar 适配层能识别美股时区，但 V1 业务只允许 A 股和港股；不要据此宣称系统支持美股。
            return ZoneId.of("America/New_York");
        }
        return ZoneId.of("Asia/Shanghai");
    }
}
