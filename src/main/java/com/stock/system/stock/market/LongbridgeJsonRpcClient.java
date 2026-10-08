package com.stock.system.stock.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one official {@code longbridge serve} process and speaks its newline-delimited JSON-RPC protocol.
 * The CLI owns OAuth token storage; this class never reads or logs the token cache.
 */
@Component
@ConditionalOnExpression("'${stock-system.market.mode:directory}' == 'longbridge' && '${stock-system.market.longbridge.transport:cli}' == 'cli'")
class LongbridgeJsonRpcClient implements LongbridgeUpstreamGateway, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LongbridgeJsonRpcClient.class);
    private static final String QUOTE_METHOD = "quote.quote";
    private static final String CANDLESTICK_METHOD = "quote.candlesticks";
    private static final String HISTORY_CANDLESTICK_BY_DATE_METHOD =
            "quote.history_candlesticks_by_date";
    private static final String INTRADAY_METHOD = "quote.intraday";
    private static final String TRADING_DAYS_METHOD = "quote.trading_days";
    private static final Duration TRADING_DAY_CACHE_TTL = Duration.ofHours(24);
    private static final int QUOTE_CACHE_MAXIMUM_SIZE = 4096;
    private static final int CANDLESTICK_CACHE_MAXIMUM_SIZE = 256;
    private static final int INTRADAY_CACHE_MAXIMUM_SIZE = 512;
    private static final int TRADING_DAY_CACHE_MAXIMUM_SIZE = 2048;

    private final ObjectMapper objectMapper;
    private final ProcessStarter processStarter;
    private final Duration requestTimeout;
    private final Duration cacheTtl;
    private final Duration candlestickCacheTtl;
    private final long minimumRequestIntervalNanos;
    private final Semaphore concurrentRequests;
    private final AtomicLong nextRequestId = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final BoundedExpiringCache<String, LongbridgeQuote> quoteCache =
            new BoundedExpiringCache<>(QUOTE_CACHE_MAXIMUM_SIZE);
    private final BoundedExpiringCache<CandlestickCacheKey, List<LongbridgeCandlestick>>
            candlestickCache = new BoundedExpiringCache<>(CANDLESTICK_CACHE_MAXIMUM_SIZE);
    private final BoundedExpiringCache<String, CachedIntraday> intradayCache =
            new BoundedExpiringCache<>(INTRADAY_CACHE_MAXIMUM_SIZE);
    private final BoundedExpiringCache<TradingDayCacheKey, Boolean> tradingDayCache =
            new BoundedExpiringCache<>(TRADING_DAY_CACHE_MAXIMUM_SIZE);
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final Object rateLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter processInput;
    private long nextRequestAtNanos;
    private volatile boolean closed;

    @Autowired
    LongbridgeJsonRpcClient(
            ObjectMapper objectMapper,
            @Value("${stock-system.market.longbridge.executable:longbridge}") String executable,
            @Value("${stock-system.market.longbridge.request-timeout:5s}") Duration requestTimeout,
            @Value("${stock-system.market.longbridge.cache-ttl:5s}") Duration cacheTtl,
            @Value("${stock-system.market.longbridge.candlestick-cache-ttl:1m}")
            Duration candlestickCacheTtl,
            @Value("${stock-system.market.longbridge.max-concurrent-requests:5}") int maxConcurrentRequests,
            @Value("${stock-system.market.longbridge.minimum-request-interval:100ms}") Duration minimumRequestInterval
    ) {
        this(
                objectMapper,
                () -> startCliProcess(executable),
                requestTimeout,
                cacheTtl,
                candlestickCacheTtl,
                maxConcurrentRequests,
                minimumRequestInterval
        );
    }

    LongbridgeJsonRpcClient(
            ObjectMapper objectMapper,
            ProcessStarter processStarter,
            Duration requestTimeout,
            Duration cacheTtl,
            int maxConcurrentRequests,
            Duration minimumRequestInterval
    ) {
        this(objectMapper, processStarter, requestTimeout, cacheTtl, Duration.ofMinutes(1),
                maxConcurrentRequests, minimumRequestInterval);
    }

    LongbridgeJsonRpcClient(
            ObjectMapper objectMapper,
            ProcessStarter processStarter,
            Duration requestTimeout,
            Duration cacheTtl,
            Duration candlestickCacheTtl,
            int maxConcurrentRequests,
            Duration minimumRequestInterval
    ) {
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Longbridge request timeout must be positive");
        }
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("Longbridge cache TTL cannot be negative");
        }
        if (candlestickCacheTtl.isNegative()) {
            throw new IllegalArgumentException("Longbridge candlestick cache TTL cannot be negative");
        }
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 5) {
            throw new IllegalArgumentException("Longbridge concurrency must be between 1 and 5");
        }
        if (minimumRequestInterval.isNegative()
                || minimumRequestInterval.compareTo(Duration.ofMillis(100)) < 0) {
            throw new IllegalArgumentException("Longbridge request interval must be at least 100 ms");
        }
        this.objectMapper = objectMapper;
        this.processStarter = processStarter;
        this.requestTimeout = requestTimeout;
        this.cacheTtl = cacheTtl;
        this.candlestickCacheTtl = candlestickCacheTtl;
        this.minimumRequestIntervalNanos = minimumRequestInterval.toNanos();
        this.concurrentRequests = new Semaphore(maxConcurrentRequests, true);
    }

    @Override
    public Map<String, LongbridgeQuote> getQuotes(List<String> requestedSymbols) {
        Set<String> symbols = new LinkedHashSet<>();
        for (String symbol : requestedSymbols) {
            if (symbol != null && !symbol.isBlank()) {
                symbols.add(symbol.trim().toUpperCase());
            }
        }
        if (symbols.isEmpty()) {
            return Map.of();
        }

        Instant now = Instant.now();
        Map<String, LongbridgeQuote> result = new LinkedHashMap<>();
        List<String> missing = symbols.stream().filter(symbol -> {
            LongbridgeQuote cached = quoteCache.getIfFresh(symbol, now);
            if (cached == null) {
                return true;
            }
            result.put(symbol, cached);
            return false;
        }).toList();

        if (!missing.isEmpty()) {
            Map<String, LongbridgeQuote> loaded = requestQuotes(missing);
            Instant expiresAt = Instant.now().plus(cacheTtl);
            loaded.forEach((symbol, quote) -> {
                quoteCache.put(symbol, quote, expiresAt);
                result.put(symbol, quote);
            });
        }
        return Map.copyOf(result);
    }

    private Map<String, LongbridgeQuote> requestQuotes(List<String> symbols) {
        ObjectNode params = objectMapper.createObjectNode();
        params.set("symbols", objectMapper.valueToTree(symbols));
        return parseQuotes(executeRequest(QUOTE_METHOD, params));
    }

    @Override
    public List<LongbridgeCandlestick> getCandlesticks(
            String rawSymbol,
            CandlestickPeriod period,
            int count
    ) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase();
        if (symbol.isEmpty() || count < 1 || count > 1000) {
            throw new IllegalArgumentException("Invalid Longbridge candlestick request");
        }
        CandlestickCacheKey key = new CandlestickCacheKey(symbol, period, count, "latest");
        Instant now = Instant.now();
        List<LongbridgeCandlestick> cached = candlestickCache.getIfFresh(key, now);
        if (cached != null) {
            return cached;
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("symbol", symbol);
        params.put("period", period.apiValue());
        params.put("count", count);
        params.put("adjust", "none");
        List<LongbridgeCandlestick> loaded = parseCandlesticks(
                executeRequest(CANDLESTICK_METHOD, params));
        candlestickCache.put(key, loaded, Instant.now().plus(candlestickCacheTtl));
        return loaded;
    }

    @Override
    public List<LongbridgeCandlestick> getCandlesticksBefore(
            String rawSymbol,
            CandlestickPeriod period,
            int count,
            Instant before
    ) {
        String symbol = normalizeCandlestickRequest(rawSymbol, count);
        LocalDate endDate = before.atZone(marketZone(symbol)).toLocalDate();
        CandlestickCacheKey key = new CandlestickCacheKey(
                symbol, period, count, "before:" + before.toEpochMilli());
        return loadCandlesticks(key, () -> {
            ObjectNode params = historyParams(symbol, period);
            params.put("end", endDate.toString());
            List<LongbridgeCandlestick> loaded = parseCandlesticks(
                    executeRequest(HISTORY_CANDLESTICK_BY_DATE_METHOD, params));
            return keepMostRecent(loaded.stream()
                    .filter(item -> item.timestamp().isBefore(before))
                    .toList(), count);
        });
    }

    @Override
    public List<LongbridgeCandlestick> getCandlesticksOnDate(
            String rawSymbol,
            CandlestickPeriod period,
            LocalDate date
    ) {
        String symbol = normalizeCandlestickRequest(rawSymbol, 1000);
        CandlestickCacheKey key = new CandlestickCacheKey(
                symbol, period, 1000, "date:" + date);
        return loadCandlesticks(key, () -> {
            ObjectNode params = historyParams(symbol, period);
            params.put("start", date.toString());
            params.put("end", date.toString());
            return parseCandlesticks(executeRequest(HISTORY_CANDLESTICK_BY_DATE_METHOD, params));
        });
    }

    private String normalizeCandlestickRequest(String rawSymbol, int count) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase();
        if (symbol.isEmpty() || count < 1 || count > 1000) {
            throw new IllegalArgumentException("Invalid Longbridge candlestick request");
        }
        return symbol;
    }

    private ObjectNode historyParams(String symbol, CandlestickPeriod period) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("symbol", symbol);
        params.put("period", period.apiValue());
        params.put("adjust", "none");
        return params;
    }

    private List<LongbridgeCandlestick> keepMostRecent(
            List<LongbridgeCandlestick> items,
            int count
    ) {
        List<LongbridgeCandlestick> sorted = items.stream()
                .sorted(Comparator.comparing(LongbridgeCandlestick::timestamp))
                .toList();
        if (sorted.size() <= count) {
            return sorted;
        }
        return List.copyOf(sorted.subList(sorted.size() - count, sorted.size()));
    }

    private List<LongbridgeCandlestick> loadCandlesticks(
            CandlestickCacheKey key,
            CandlestickLoader loader
    ) {
        Instant now = Instant.now();
        List<LongbridgeCandlestick> cached = candlestickCache.getIfFresh(key, now);
        if (cached != null) {
            return cached;
        }
        List<LongbridgeCandlestick> loaded = loader.load();
        candlestickCache.put(key, loaded, Instant.now().plus(candlestickCacheTtl));
        return loaded;
    }

    @Override
    public List<LongbridgeIntradayPoint> getIntraday(String rawSymbol) {
        String symbol = normalizeCandlestickRequest(rawSymbol, 1);
        Instant now = Instant.now();
        LocalDate marketDate = now.atZone(marketZone(symbol)).toLocalDate();
        CachedIntraday cached = intradayCache.getIfFresh(symbol, now);
        if (cached != null && cached.marketDate().equals(marketDate)) {
            return cached.items();
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("symbol", symbol);
        List<LongbridgeIntradayPoint> loaded = parseIntraday(
                executeRequest(INTRADAY_METHOD, params));
        intradayCache.put(
                symbol,
                new CachedIntraday(loaded, marketDate),
                Instant.now().plus(cacheTtl)
        );
        return loaded;
    }

    @Override
    public boolean isTradingDay(String rawSymbol, LocalDate date) {
        String symbol = normalizeCandlestickRequest(rawSymbol, 1);
        String market = marketCode(symbol);
        TradingDayCacheKey key = new TradingDayCacheKey(market, date);
        Instant now = Instant.now();
        Boolean cached = tradingDayCache.getIfFresh(key, now);
        if (cached != null) {
            return cached;
        }
        boolean tradingDay = requestTradingDay(market, date);
        tradingDayCache.put(key, tradingDay, now.plus(TRADING_DAY_CACHE_TTL));
        return tradingDay;
    }

    private boolean requestTradingDay(String market, LocalDate date) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("market", market);
        params.put("start", date.toString());
        params.put("end", date.toString());
        JsonNode result = executeRequest(TRADING_DAYS_METHOD, params);
        return containsDate(result.path("trading_days"), date)
                || containsDate(result.path("half_trading_days"), date);
    }

    private boolean containsDate(JsonNode dates, LocalDate expected) {
        if (!dates.isArray()) {
            throw new LongbridgeUpstreamException("Longbridge trading-day result has an unexpected shape");
        }
        for (JsonNode date : dates) {
            if (expected.toString().equals(date.asText())) {
                return true;
            }
        }
        return false;
    }

    private String marketCode(String symbol) {
        if (symbol.endsWith(".HK")) {
            return "HK";
        }
        if (symbol.endsWith(".SH") || symbol.endsWith(".SZ") || symbol.endsWith(".BJ")) {
            return "CN";
        }
        throw new IllegalArgumentException("Unsupported Longbridge trading-day market");
    }

    private ZoneId marketZone(String symbol) {
        if (symbol.endsWith(".US")) {
            // 这里只负责供应商时间转换；V1 的数据库和业务入口仍只接受 A 股、港股。
            return ZoneId.of("America/New_York");
        }
        if (symbol.endsWith(".HK")) {
            return ZoneId.of("Asia/Hong_Kong");
        }
        return ZoneId.of("Asia/Shanghai");
    }

    private JsonNode executeRequest(String method, ObjectNode params) {
        boolean acquired = false;
        long requestId = nextRequestId.incrementAndGet();
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        try {
            acquired = concurrentRequests.tryAcquire(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new LongbridgeUpstreamException("Longbridge request queue timed out");
            }
            awaitRateSlot();
            pendingRequests.put(requestId, response);
            writeRequest(requestId, method, params);
            return response.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LongbridgeUpstreamException("Longbridge request was interrupted", exception);
        } catch (TimeoutException exception) {
            stopBrokenProcess();
            throw new LongbridgeUpstreamException("Longbridge request timed out", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof LongbridgeUpstreamException cliException) {
                throw cliException;
            }
            throw new LongbridgeUpstreamException("Longbridge request failed", cause);
        } finally {
            pendingRequests.remove(requestId);
            if (acquired) {
                concurrentRequests.release();
            }
        }
    }

    private void awaitRateSlot() throws InterruptedException {
        synchronized (rateLock) {
            long now = System.nanoTime();
            long waitNanos = Math.max(0, nextRequestAtNanos - now);
            if (waitNanos > 0) {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            }
            nextRequestAtNanos = System.nanoTime() + minimumRequestIntervalNanos;
        }
    }

    private void writeRequest(long requestId, String method, ObjectNode params) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId);
        request.put("method", method);
        request.set("params", params);

        synchronized (writeLock) {
            try {
                BufferedWriter writer = ensureProcessInput();
                writer.write(objectMapper.writeValueAsString(request));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                stopBrokenProcess();
                throw new LongbridgeUpstreamException("Cannot communicate with Longbridge CLI", exception);
            }
        }
    }

    private BufferedWriter ensureProcessInput() {
        synchronized (lifecycleLock) {
            if (closed) {
                throw new LongbridgeUpstreamException("Longbridge CLI client is closed");
            }
            if (process != null && process.isAlive() && processInput != null) {
                return processInput;
            }
            try {
                Process started = processStarter.start();
                process = started;
                processInput = new BufferedWriter(new OutputStreamWriter(
                        started.getOutputStream(), StandardCharsets.UTF_8));
                startReaderThreads(started);
                LOGGER.info("Longbridge CLI sidecar started");
                return processInput;
            } catch (IOException exception) {
                throw new LongbridgeUpstreamException(
                        "Cannot start Longbridge CLI; verify installation, executable path, and service-account login",
                        exception
                );
            }
        }
    }

    private void startReaderThreads(Process ownedProcess) {
        Thread stdoutReader = new Thread(() -> readResponses(ownedProcess), "longbridge-json-rpc-reader");
        stdoutReader.setDaemon(true);
        stdoutReader.start();

        Thread stderrDrainer = new Thread(() -> drainErrorStream(ownedProcess), "longbridge-stderr-drainer");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();
    }

    private void readResponses(Process ownedProcess) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ownedProcess.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleResponse(line);
            }
            handleProcessExit(ownedProcess, null);
        } catch (IOException exception) {
            handleProcessExit(ownedProcess, exception);
        }
    }

    private void handleResponse(String line) {
        try {
            JsonNode json = objectMapper.readTree(line);
            JsonNode idNode = json.get("id");
            if (idNode == null || !idNode.canConvertToLong()) {
                return;
            }
            CompletableFuture<JsonNode> pending = pendingRequests.get(idNode.longValue());
            if (pending == null) {
                return;
            }
            JsonNode error = json.get("error");
            if (error != null && !error.isNull()) {
                String code = providerErrorCode(error);
                String providerMessage = error.path("message").asText("");
                pending.completeExceptionally(new LongbridgeUpstreamException(
                        code, "Longbridge RPC request was rejected with code " + code,
                        providerMessage));
                return;
            }
            JsonNode result = json.get("result");
            if (result == null) {
                pending.completeExceptionally(new LongbridgeUpstreamException("Longbridge response has no result"));
                return;
            }
            pending.complete(result);
        } catch (Exception exception) {
            LOGGER.warn("Ignored a malformed Longbridge CLI response");
        }
    }

    private void drainErrorStream(Process ownedProcess) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                ownedProcess.getErrorStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // Intentionally do not copy upstream stderr into project logs: it may contain account details.
            }
        } catch (IOException ignored) {
            // Process exit closes this stream.
        }
    }

    private Map<String, LongbridgeQuote> parseQuotes(JsonNode result) {
        JsonNode quoteArray = result.isArray() ? result : result.path("securities");
        if (!quoteArray.isArray()) {
            throw new LongbridgeUpstreamException("Longbridge quote result has an unexpected shape");
        }
        Map<String, LongbridgeQuote> quotes = new LinkedHashMap<>();
        Instant retrievedAt = Instant.now();
        for (JsonNode item : quoteArray) {
            String symbol = item.path("symbol").asText("").trim().toUpperCase();
            String lastDone = item.path("last_done").asText("");
            String timestamp = item.path("timestamp").asText("");
            if (symbol.isEmpty()) {
                continue;
            }
            try {
                LongbridgeQuote quote = new LongbridgeQuote(
                        symbol,
                        decimalOrNull(lastDone),
                        instantOrNull(timestamp),
                        item.path("trade_status").asText(""),
                        retrievedAt
                );
                quotes.put(symbol, quote);
            } catch (RuntimeException ignored) {
                // A malformed symbol is unavailable; valid siblings from the same batch remain usable.
            }
        }
        return quotes;
    }

    private BigDecimal decimalOrNull(String value) {
        try { return value.isBlank() ? null : new BigDecimal(value); }
        catch (RuntimeException invalid) { return null; }
    }

    private Instant instantOrNull(String value) {
        try { return value.isBlank() ? null : Instant.parse(value); }
        catch (RuntimeException invalid) { return null; }
    }

    private List<LongbridgeCandlestick> parseCandlesticks(JsonNode result) {
        JsonNode items = result.isArray() ? result : result.path("candlesticks");
        if (!items.isArray()) {
            throw new LongbridgeUpstreamException("Longbridge candlestick result has an unexpected shape");
        }
        java.util.ArrayList<LongbridgeCandlestick> candlesticks = new java.util.ArrayList<>();
        Instant retrievedAt = Instant.now();
        for (JsonNode item : items) {
            try {
                Instant timestamp = parseTimestamp(item.hasNonNull("timestamp")
                        ? item.get("timestamp") : item.get("time"));
                BigDecimal open = new BigDecimal(item.path("open").asText());
                BigDecimal high = new BigDecimal(item.path("high").asText());
                BigDecimal low = new BigDecimal(item.path("low").asText());
                BigDecimal close = new BigDecimal(item.path("close").asText());
                long volume = item.path("volume").asLong();
                BigDecimal turnover = new BigDecimal(item.path("turnover").asText("0"));
                if (timestamp != null && open.signum() > 0 && high.compareTo(open.max(close)) >= 0
                        && low.compareTo(open.min(close)) <= 0 && low.signum() > 0
                        && volume >= 0 && turnover.signum() >= 0) {
                    candlesticks.add(new LongbridgeCandlestick(
                            timestamp, open, high, low, close, volume, turnover, retrievedAt));
                }
            } catch (RuntimeException ignored) {
                // Invalid rows are excluded; a wholly invalid response becomes unavailable upstream.
            }
        }
        return List.copyOf(candlesticks);
    }

    private List<LongbridgeIntradayPoint> parseIntraday(JsonNode result) {
        JsonNode items = result.isArray() ? result : result.path("lines");
        if (!items.isArray()) {
            items = result.path("intraday");
        }
        if (!items.isArray()) {
            throw new LongbridgeUpstreamException("Longbridge intraday result has an unexpected shape");
        }
        java.util.ArrayList<LongbridgeIntradayPoint> points = new java.util.ArrayList<>();
        Instant retrievedAt = Instant.now();
        for (JsonNode item : items) {
            try {
                Instant timestamp = parseTimestamp(item.hasNonNull("timestamp")
                        ? item.get("timestamp") : item.get("time"));
                BigDecimal price = new BigDecimal(item.path("price").asText());
                JsonNode averageNode = item.hasNonNull("avg_price")
                        ? item.get("avg_price") : item.get("average_price");
                BigDecimal average = averageNode == null
                        ? price : new BigDecimal(averageNode.asText());
                long volume = item.path("volume").asLong();
                BigDecimal turnover = new BigDecimal(item.path("turnover").asText("0"));
                if (timestamp != null && price.signum() > 0 && average.signum() > 0
                        && volume >= 0 && turnover.signum() >= 0) {
                    points.add(new LongbridgeIntradayPoint(
                            timestamp, price, average, volume, turnover, retrievedAt));
                }
            } catch (RuntimeException ignored) {
                // Invalid rows are excluded; a wholly invalid response becomes unavailable upstream.
            }
        }
        return points.stream()
                .sorted(Comparator.comparing(LongbridgeIntradayPoint::timestamp))
                .toList();
    }

    private Instant parseTimestamp(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return Instant.ofEpochSecond(node.longValue());
        }
        String value = node.asText("");
        if (value.matches("\\d+")) {
            return Instant.ofEpochSecond(Long.parseLong(value));
        }
        return value.isEmpty() ? null : Instant.parse(value);
    }

    private String providerErrorCode(JsonNode error) {
        JsonNode data = error.path("data");
        if (data.isObject() && data.hasNonNull("code")) {
            return data.path("code").asText();
        }
        String message = error.path("message").asText("");
        if (message.contains("301607")) {
            return "301607";
        }
        return error.path("code").asText("unknown");
    }

    private void handleProcessExit(Process ownedProcess, Exception cause) {
        synchronized (lifecycleLock) {
            if (process != ownedProcess) {
                return;
            }
            process = null;
            processInput = null;
        }
        LongbridgeUpstreamException failure = new LongbridgeUpstreamException(
                "Longbridge CLI sidecar exited" + (cause == null ? "" : " unexpectedly"), cause);
        pendingRequests.values().forEach(request -> request.completeExceptionally(failure));
        if (!closed) {
            LOGGER.warn("Longbridge CLI sidecar exited; the next quote request will try to restart it");
        }
    }

    private void stopBrokenProcess() {
        Process broken;
        synchronized (lifecycleLock) {
            broken = process;
            process = null;
            processInput = null;
        }
        LongbridgeUpstreamException failure = new LongbridgeUpstreamException(
                "Longbridge CLI sidecar was stopped after a transport failure");
        pendingRequests.values().forEach(request -> request.completeExceptionally(failure));
        if (broken != null && broken.isAlive()) {
            broken.destroy();
        }
    }

    static Process startCliProcess(String configuredExecutable) throws IOException {
        String executable = configuredExecutable;
        if ("longbridge".equalsIgnoreCase(configuredExecutable)
                && System.getProperty("os.name", "").toLowerCase().contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                Path installedCli = Path.of(localAppData, "Programs", "longbridge", "longbridge.exe");
                if (Files.isRegularFile(installedCli)) {
                    executable = installedCli.toString();
                }
            }
        }
        return new ProcessBuilder(executable, "serve").start();
    }

    @Override
    @PreDestroy
    public void close() {
        Process ownedProcess;
        BufferedWriter writer;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            ownedProcess = process;
            writer = processInput;
            process = null;
            processInput = null;
        }
        pendingRequests.values().forEach(request -> request.completeExceptionally(
                new LongbridgeUpstreamException("Longbridge CLI client is shutting down")));
        pendingRequests.clear();
        closeQuietly(writer);
        stopOwnedProcess(ownedProcess);
        if (ownedProcess != null) {
            LOGGER.info("Longbridge CLI sidecar stopped");
        }
    }

    private void stopOwnedProcess(Process ownedProcess) {
        if (ownedProcess == null || !ownedProcess.isAlive()) {
            return;
        }
        try {
            if (!ownedProcess.waitFor(2, TimeUnit.SECONDS)) {
                ownedProcess.destroy();
            }
            if (ownedProcess.isAlive() && !ownedProcess.waitFor(2, TimeUnit.SECONDS)) {
                ownedProcess.destroyForcibly();
                ownedProcess.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ownedProcess.destroy();
        }
    }

    private void closeQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Process shutdown below is the final cleanup boundary.
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start() throws IOException;
    }

    private record CandlestickCacheKey(
            String symbol,
            CandlestickPeriod period,
            int count,
            String boundary
    ) {
    }

    private record CachedIntraday(
            List<LongbridgeIntradayPoint> items,
            LocalDate marketDate
    ) {
    }

    private record TradingDayCacheKey(String market, LocalDate date) {
    }

    @FunctionalInterface
    private interface CandlestickLoader {
        List<LongbridgeCandlestick> load();
    }
}
