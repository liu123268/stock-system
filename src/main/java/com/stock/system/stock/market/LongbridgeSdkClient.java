package com.stock.system.stock.market;

import com.longbridge.Market;
import com.longbridge.OpenApiException;
import com.longbridge.quote.AdjustType;
import com.longbridge.quote.Candlestick;
import com.longbridge.quote.IntradayLine;
import com.longbridge.quote.MarketTradingDays;
import com.longbridge.quote.Period;
import com.longbridge.quote.QuoteContext;
import com.longbridge.quote.SecurityQuote;
import com.longbridge.quote.TradeSessions;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/** Official Java SDK transport. One lazily owned quote context per backend; no CLI process. */
@Component
@ConditionalOnExpression("'${stock-system.market.mode:directory}' == 'longbridge' && '${stock-system.market.longbridge.transport:cli}' == 'sdk'")
class LongbridgeSdkClient implements LongbridgeUpstreamGateway, AutoCloseable {
    private final SessionFactory sessionFactory;
    private final Duration timeout;
    private final Duration quoteTtl;
    private final Duration candlestickTtl;
    private final Duration interval;
    private final ReentrantLock requestLock = new ReentrantLock(true);
    private final BoundedExpiringCache<String, LongbridgeQuote> quotes = new BoundedExpiringCache<>(4096);
    private final BoundedExpiringCache<BarKey, List<LongbridgeCandlestick>> bars = new BoundedExpiringCache<>(256);
    private final BoundedExpiringCache<IntradayKey, List<LongbridgeIntradayPoint>> intraday = new BoundedExpiringCache<>(512);
    private final BoundedExpiringCache<DayKey, Boolean> tradingDays = new BoundedExpiringCache<>(2048);
    private LongbridgeSdkSession session;
    private long nextRequestNanos;
    private volatile boolean closed;

    @Autowired
    LongbridgeSdkClient(
            @Value("${stock-system.market.longbridge.client-id:}") String clientId,
            @Value("${stock-system.market.longbridge.request-timeout:5s}") Duration timeout,
            @Value("${stock-system.market.longbridge.cache-ttl:5s}") Duration quoteTtl,
            @Value("${stock-system.market.longbridge.candlestick-cache-ttl:1m}") Duration candlestickTtl,
            @Value("${stock-system.market.longbridge.minimum-request-interval:100ms}") Duration interval
    ) {
        this(() -> LongbridgeSdkSession.open(clientId, timeout), timeout, quoteTtl, candlestickTtl, interval);
    }

    LongbridgeSdkClient(SessionFactory sessionFactory, Duration timeout, Duration quoteTtl,
                        Duration candlestickTtl, Duration interval) {
        if (timeout.isNegative() || timeout.isZero() || quoteTtl.isNegative()
                || candlestickTtl.isNegative() || interval.compareTo(Duration.ofMillis(100)) < 0) {
            throw new IllegalArgumentException("Invalid Longbridge SDK timing configuration");
        }
        this.sessionFactory = sessionFactory;
        this.timeout = timeout;
        this.quoteTtl = quoteTtl;
        this.candlestickTtl = candlestickTtl;
        this.interval = interval;
    }

    @Override
    public Map<String, LongbridgeQuote> getQuotes(List<String> requested) {
        ensureOpen();
        List<String> symbols = requested.stream().filter(Objects::nonNull).filter(s -> !s.isBlank())
                .map(LongbridgeSdkClient::normalize).distinct().toList();
        Map<String, LongbridgeQuote> result = new LinkedHashMap<>();
        Instant now = Instant.now();
        List<String> missing = symbols.stream().filter(symbol -> {
            LongbridgeQuote cached = quotes.getIfFresh(symbol, now);
            if (cached == null) return true;
            result.put(symbol, cached);
            return false;
        }).toList();
        if (!missing.isEmpty()) {
            SecurityQuote[] loaded = execute(context -> context.getQuote(missing.toArray(String[]::new)));
            Instant retrievedAt = Instant.now();
            for (SecurityQuote item : loaded) {
                if (item == null || !missing.contains(item.getSymbol()) || item.getLastDone() == null
                        || item.getLastDone().signum() <= 0 || item.getTimestamp() == null) continue;
                LongbridgeQuote quote = new LongbridgeQuote(item.getSymbol(), item.getLastDone(),
                        item.getTimestamp().toInstant(),
                        item.getTradeStatus() == null ? "" : item.getTradeStatus().name(), retrievedAt);
                quotes.put(item.getSymbol(), quote, retrievedAt.plus(quoteTtl));
                result.put(item.getSymbol(), quote);
            }
        }
        return Map.copyOf(result);
    }

    @Override
    public List<LongbridgeCandlestick> getCandlesticks(String rawSymbol, CandlestickPeriod period, int count) {
        String symbol = validateBars(rawSymbol, count);
        return loadBars(new BarKey(symbol, period, count, "latest"), context -> context.getCandlesticks(
                symbol, sdkPeriod(period), count, AdjustType.NoAdjust, TradeSessions.Intraday));
    }

    @Override
    public List<LongbridgeCandlestick> getCandlesticksBefore(String rawSymbol, CandlestickPeriod period,
                                                           int count, Instant before) {
        String symbol = validateBars(rawSymbol, count);
        LocalDate end = before.atZone(MarketTradingSessions.zoneFor(symbol)).toLocalDate();
        List<LongbridgeCandlestick> loaded = loadBars(new BarKey(symbol, period, count, "before:" + before),
                context -> context.getHistoryCandlesticksByDate(symbol, sdkPeriod(period),
                        AdjustType.NoAdjust, null, end, TradeSessions.Intraday));
        List<LongbridgeCandlestick> eligible = loaded.stream().filter(bar -> bar.timestamp().isBefore(before)).toList();
        return List.copyOf(eligible.subList(Math.max(0, eligible.size() - count), eligible.size()));
    }

    @Override
    public List<LongbridgeCandlestick> getCandlesticksOnDate(String rawSymbol, CandlestickPeriod period, LocalDate date) {
        String symbol = validateBars(rawSymbol, 1000);
        return loadBars(new BarKey(symbol, period, 1000, "date:" + date),
                context -> context.getHistoryCandlesticksByDate(symbol, sdkPeriod(period),
                        AdjustType.NoAdjust, date, date, TradeSessions.Intraday));
    }

    private List<LongbridgeCandlestick> loadBars(BarKey key, SdkRequest<Candlestick[]> request) {
        ensureOpen();
        List<LongbridgeCandlestick> cached = bars.getIfFresh(key, Instant.now());
        if (cached != null) return cached;
        Candlestick[] loaded = execute(request);
        Instant retrievedAt = Instant.now();
        List<LongbridgeCandlestick> converted = Arrays.stream(loaded).filter(LongbridgeSdkClient::validBar)
                .map(bar -> new LongbridgeCandlestick(bar.getTimestamp().toInstant(), bar.getOpen(),
                        bar.getHigh(), bar.getLow(), bar.getClose(), bar.getVolume(), bar.getTurnover(), retrievedAt))
                .sorted(Comparator.comparing(LongbridgeCandlestick::timestamp)).toList();
        bars.put(key, converted, retrievedAt.plus(candlestickTtl));
        return converted;
    }

    @Override
    public List<LongbridgeIntradayPoint> getIntraday(String rawSymbol) {
        ensureOpen();
        String symbol = normalize(rawSymbol);
        IntradayKey key = new IntradayKey(symbol, LocalDate.now(MarketTradingSessions.zoneFor(symbol)));
        List<LongbridgeIntradayPoint> cached = intraday.getIfFresh(key, Instant.now());
        if (cached != null) return cached;
        IntradayLine[] loaded = execute(context -> context.getIntraday(symbol, TradeSessions.Intraday));
        Instant retrievedAt = Instant.now();
        List<LongbridgeIntradayPoint> converted = Arrays.stream(loaded).filter(item -> item != null
                        && item.getTimestamp() != null && positive(item.getPrice()) && positive(item.getAvgPrice())
                        && item.getVolume() >= 0 && item.getTurnover() != null && item.getTurnover().signum() >= 0)
                .map(item -> new LongbridgeIntradayPoint(item.getTimestamp().toInstant(), item.getPrice(),
                        item.getAvgPrice(), item.getVolume(), item.getTurnover(), retrievedAt))
                .sorted(Comparator.comparing(LongbridgeIntradayPoint::timestamp)).toList();
        intraday.put(key, converted, retrievedAt.plus(quoteTtl));
        return converted;
    }

    @Override
    public boolean isTradingDay(String rawSymbol, LocalDate date) {
        ensureOpen();
        String symbol = normalize(rawSymbol);
        MarketTradingSessions.zoneFor(symbol); // Reject unsupported markets before contacting the provider.
        Market market = symbol.endsWith(".HK") ? Market.HK : Market.CN;
        DayKey key = new DayKey(market, date);
        Boolean cached = tradingDays.getIfFresh(key, Instant.now());
        if (cached != null) return cached;
        MarketTradingDays loaded = execute(context -> context.getTradingDays(market, date, date));
        if (loaded.getTradingDays() == null || loaded.getHalfTradingDays() == null) {
            throw new LongbridgeUpstreamException("Longbridge SDK trading-day result has an unexpected shape");
        }
        boolean result = Arrays.asList(loaded.getTradingDays()).contains(date)
                || Arrays.asList(loaded.getHalfTradingDays()).contains(date);
        tradingDays.put(key, result, Instant.now().plus(Duration.ofHours(24)));
        return result;
    }

    private <T> T execute(SdkRequest<T> request) {
        boolean acquired = false;
        try {
            acquired = requestLock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) throw new LongbridgeUpstreamException("Longbridge SDK request queue timed out");
            ensureOpen();
            if (session == null) session = sessionFactory.open();
            long wait = nextRequestNanos - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            nextRequestNanos = System.nanoTime() + interval.toNanos();
            T result = request.call(session.context()).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result == null) throw new LongbridgeUpstreamException("Longbridge SDK returned an empty result");
            return result;
        } catch (InterruptedException failure) {
            if (acquired) discardSession();
            Thread.currentThread().interrupt();
            throw new LongbridgeUpstreamException("Longbridge SDK request was interrupted");
        } catch (TimeoutException failure) {
            discardSession();
            throw new LongbridgeUpstreamException("Longbridge SDK request timed out");
        } catch (ExecutionException failure) {
            // A known API error keeps the connection. Transport/auth failure discards it before retry.
            if (!(failure.getCause() instanceof OpenApiException api) || api.getCode() == null) discardSession();
            throw safeFailure(failure.getCause());
        } catch (OpenApiException failure) {
            if (failure.getCode() == null) discardSession();
            throw safeFailure(failure);
        } catch (LongbridgeUpstreamException failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            if (acquired) discardSession();
            throw new LongbridgeUpstreamException("Longbridge SDK unavailable; check local OAuth setup and network");
        } finally {
            if (acquired) requestLock.unlock();
        }
    }

    static LongbridgeUpstreamException safeFailure(Throwable failure) {
        if (failure instanceof OpenApiException api && api.getCode() != null) {
            // Preserve only the error code and the allowlisted minute-history date, never raw messages.
            String date = new LongbridgeUpstreamException("", "", api.getMessage()).minuteHistoryBeginDate()
                    .map(value -> "out of minute candlestick limit begin date:" + value).orElse(null);
            return new LongbridgeUpstreamException(api.getCode().toString(), "Longbridge SDK request failed", date);
        }
        return new LongbridgeUpstreamException("Longbridge SDK request failed; check local OAuth setup and network");
    }

    private void ensureOpen() {
        if (closed) throw new LongbridgeUpstreamException("Longbridge SDK client is closed");
    }

    private void discardSession() {
        LongbridgeSdkSession old = session;
        session = null;
        if (old != null) old.close();
    }

    @Override
    @PreDestroy
    public void close() {
        requestLock.lock();
        try {
            closed = true;
            discardSession();
        } finally {
            requestLock.unlock();
        }
    }

    private static String normalize(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("Invalid Longbridge symbol");
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String validateBars(String symbol, int count) {
        if (count < 1 || count > 1000) throw new IllegalArgumentException("Invalid Longbridge candlestick count");
        return normalize(symbol);
    }

    private static boolean positive(java.math.BigDecimal value) { return value != null && value.signum() > 0; }

    private static boolean validBar(Candlestick bar) {
        return bar != null && bar.getTimestamp() != null && positive(bar.getOpen()) && positive(bar.getClose())
                && positive(bar.getHigh()) && positive(bar.getLow())
                && bar.getHigh().compareTo(bar.getOpen().max(bar.getClose())) >= 0
                && bar.getLow().compareTo(bar.getOpen().min(bar.getClose())) <= 0
                && bar.getHigh().compareTo(bar.getLow()) >= 0 && bar.getVolume() >= 0
                && bar.getTurnover() != null && bar.getTurnover().signum() >= 0;
    }

    static Period sdkPeriod(CandlestickPeriod period) {
        return switch (period) {
            case MINUTE_1 -> Period.Min_1;
            case MINUTE_5 -> Period.Min_5;
            case MINUTE_15 -> Period.Min_15;
            case MINUTE_30 -> Period.Min_30;
            case HOUR_1 -> Period.Min_60;
            case DAY -> Period.Day;
            case WEEK -> Period.Week;
            case MONTH -> Period.Month;
            case YEAR -> Period.Year;
        };
    }

    @FunctionalInterface interface SessionFactory { LongbridgeSdkSession open() throws Exception; }
    @FunctionalInterface private interface SdkRequest<T> { CompletableFuture<T> call(QuoteContext context) throws OpenApiException; }
    private record BarKey(String symbol, CandlestickPeriod period, int count, String boundary) { }
    private record IntradayKey(String symbol, LocalDate date) { }
    private record DayKey(Market market, LocalDate date) { }
}
