import com.google.gson.*;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Official endpoint only. No secret or provider response body is logged. */
final class DeepSeekExperimentClient implements ResearchAgentExperiment.ModelClient {
    static final BigDecimal LIMIT_CNY = new BigDecimal("10.00");
    // Conservative input ceiling exceeds the advertised 1M context; bytes are not treated as tokens.
    static final long INPUT_TOKEN_CEILING = 2_000_000;
    static final BigDecimal INPUT_RATE = new BigDecimal("2");
    static final BigDecimal OUTPUT_RATE = new BigDecimal("8");
    static final String PRICE_VERSION = "official-flash-2026-10-09-peak";
    static final BigDecimal RESERVE = charge(INPUT_TOKEN_CEILING, ResearchAgentExperiment.MAX_OUTPUT_TOKENS);
    private final String apiKey;
    private final BudgetLedger ledger;
    private final Path events;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private DeepSeekExperimentClient(String apiKey, BudgetLedger ledger, Path events) {
        this.apiKey = apiKey;
        this.ledger = ledger;
        this.events = events;
    }

    static void run(Path configPath, Path output) throws Exception {
        Properties config = new Properties();
        if (!Files.isRegularFile(configPath)) throw failure("CONFIG_MISSING");
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) { config.load(reader); }
        String key = config.getProperty("deepseek.api-key", "").trim();
        String base = config.getProperty("deepseek.base-url", "https://api.deepseek.com").trim();
        String model = config.getProperty("deepseek.model", ResearchAgentExperiment.MODEL).trim();
        if (key.length() < 16 || key.length() > 256 || key.chars().anyMatch(Character::isWhitespace)) throw failure("KEY_NOT_CONFIGURED");
        if (!"https://api.deepseek.com".equals(base) || !ResearchAgentExperiment.MODEL.equals(model)) throw failure("UNVERIFIED_PROVIDER_OR_MODEL");
        Files.createDirectories(output);
        String runId = UUID.randomUUID().toString();
        Path results = output.resolve("live-" + runId + ".jsonl");
        Path events = output.resolve("usage-" + runId + ".jsonl");
        try (BudgetLedger ledger = new BudgetLedger(output.resolve("budget.jsonl"))) {
            DeepSeekExperimentClient client = new DeepSeekExperimentClient(key, ledger, events);
            client.checkBalance();
            for (ResearchAgentExperiment.Sample sample : ResearchAgentExperiment.samples()) {
                JsonObject result = ResearchAgentExperiment.runSample(sample, client);
                append(results, result);
                System.out.println("LIVE_SAMPLE_REVIEW_REQUIRED sample=" + sample.id()
                        + " modelCalls=" + result.get("modelCalls") + " toolCalls=" + result.get("toolCalls"));
            }
            System.out.println("AGENT_LAB_LIVE_END samples=15 manualReviewRequired=true feeUpperCny="
                    + ledger.total().toPlainString() + " evidence=" + results.getFileName());
        }
    }

    private void checkBalance() throws Exception {
        JsonObject balance = exchange("GET", "/user/balance", null, System.nanoTime() + 10_000_000_000L);
        if (!balance.has("is_available") || !balance.get("is_available").isJsonPrimitive()
                || !balance.getAsJsonPrimitive("is_available").isBoolean() || !balance.get("is_available").getAsBoolean())
            throw failure("BALANCE_UNAVAILABLE");
        BigDecimal cny = BigDecimal.ZERO;
        for (JsonElement element : ResearchAgentExperiment.requiredArray(balance, "balance_infos", 5)) {
            if (element.isJsonObject() && "CNY".equals(ResearchAgentExperiment.string(element.getAsJsonObject(), "currency")))
                cny = cny.add(new BigDecimal(ResearchAgentExperiment.requiredString(element.getAsJsonObject(), "total_balance", 40)));
        }
        if (cny.compareTo(RESERVE) < 0) throw failure("BALANCE_BELOW_CONSERVATIVE_RESERVE");
        System.out.println("DEEPSEEK_BALANCE_CHECK_OK currency=CNY rawBalanceLogged=false");
    }

    public JsonObject complete(JsonObject request, long deadlineNanos) throws Exception {
        String body = ResearchAgentExperiment.JSON.toJson(request);
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > ResearchAgentExperiment.MAX_REQUEST_BYTES) throw failure("INPUT_TOO_LARGE");
        if (deadlineNanos <= System.nanoTime()) throw failure("TURN_TIMEOUT");
        String id = UUID.randomUUID().toString();
        ledger.reserve(id, bytes); // Persist before sending; unknown usage keeps this reservation across restarts.
        JsonObject response = exchange("POST", "/chat/completions", body, deadlineNanos);
        JsonObject usage = response.getAsJsonObject("usage");
        long input = tokenCount(usage, "prompt_tokens", INPUT_TOKEN_CEILING);
        long generated = tokenCount(usage, "completion_tokens", ResearchAgentExperiment.MAX_OUTPUT_TOKENS);
        String returnedModel = ResearchAgentExperiment.requiredString(response, "model", 100);
        if (!Set.of("deepseek-flash", "deepseek-v4-flash", "deepseek-v4.1-flash", "deepseek-v4-flash-vision-exp")
                .contains(returnedModel)) throw failure("UNEXPECTED_RETURNED_MODEL");
        BigDecimal feeUpper = charge(input, generated);
        ledger.settle(id, feeUpper);
        append(events, ResearchAgentExperiment.object("requestId", id, "requestedModel", ResearchAgentExperiment.MODEL,
                "returnedModel", returnedModel, "promptVersion", ResearchAgentExperiment.PROMPT_VERSION,
                "priceVersion", PRICE_VERSION, "inputBytes", bytes, "promptTokens", input,
                "completionTokens", generated, "feeUpperCny", feeUpper.toPlainString(), "recordedAt", Instant.now().toString()));
        return response;
    }

    private JsonObject exchange(String method, String path, String body, long deadlineNanos) throws Exception {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw failure("TURN_TIMEOUT");
        Duration timeout = Duration.ofNanos(Math.min(remaining, 30_000_000_000L));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://api.deepseek.com" + path))
                .timeout(timeout).header("Authorization", "Bearer " + apiKey).header("Accept", "application/json");
        if ("POST".equals(method)) builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        else builder.GET();
        var future = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw failure("PROVIDER_HTTP_" + response.statusCode());
            if (response.body().length > 1024 * 1024) throw failure("RESPONSE_TOO_LARGE");
            return ResearchAgentExperiment.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (Exception failure) {
            future.cancel(true);
            throw failure;
        }
    }

    static long tokenCount(JsonObject usage, String name, long max) {
        try {
            if (usage == null || !usage.has(name) || !usage.get(name).isJsonPrimitive()
                    || !usage.getAsJsonPrimitive(name).isNumber()) throw failure("MISSING_USAGE");
            long value = usage.get(name).getAsBigDecimal().longValueExact();
            if (value < 0 || value > max) throw failure("UNEXPECTED_USAGE");
            return value;
        } catch (ArithmeticException malformed) { throw failure("UNEXPECTED_USAGE"); }
    }

    static BigDecimal charge(long input, long output) {
        return BigDecimal.valueOf(input).multiply(INPUT_RATE).add(BigDecimal.valueOf(output).multiply(OUTPUT_RATE))
                .divide(new BigDecimal("1000000"));
    }
    static void append(Path file, JsonObject event) throws Exception {
        Files.writeString(file, ResearchAgentExperiment.JSON.toJson(event) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    static ResearchAgentExperiment.LabFailure failure(String code) { return new ResearchAgentExperiment.LabFailure(code); }

    /** The lock serializes lab runs; this ledger is separate from generated answers. */
    static final class BudgetLedger implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final Map<String, BigDecimal> held = new HashMap<>();
        private BigDecimal spent = BigDecimal.ZERO;

        BudgetLedger(Path file) throws Exception {
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock acquired;
            try { acquired = channel.tryLock(); }
            catch (Exception failure) { channel.close(); throw failure; }
            if (acquired == null) { channel.close(); throw failure("ANOTHER_LAB_RUNNING"); }
            lock = acquired;
            try {
                if (channel.size() > 1024 * 1024) throw failure("LEDGER_TOO_LARGE");
                ByteBuffer bytes = ByteBuffer.allocate((int) channel.size());
                while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
                bytes.flip();
                for (String line : StandardCharsets.UTF_8.decode(bytes).toString().split("\\R")) {
                    if (!line.isBlank()) apply(ResearchAgentExperiment.parse(line));
                }
            } catch (Exception failure) { close(); throw failure; }
        }

        BigDecimal total() { return held.values().stream().reduce(spent, BigDecimal::add); }

        void reserve(String id, int inputBytes) throws Exception {
            if (held.containsKey(id) || inputBytes < 0 || inputBytes > ResearchAgentExperiment.MAX_REQUEST_BYTES)
                throw failure("INVALID_RESERVATION");
            if (total().add(RESERVE).compareTo(LIMIT_CNY) > 0) throw failure("EXPERIMENT_BUDGET_EXHAUSTED");
            write(ResearchAgentExperiment.object("event", "reserve", "requestId", id, "amountCny", RESERVE.toPlainString(),
                    "priceVersion", PRICE_VERSION, "inputBytes", inputBytes, "time", Instant.now().toString()));
        }

        void settle(String id, BigDecimal amount) throws Exception {
            if (!held.containsKey(id) || amount.signum() < 0 || amount.compareTo(held.get(id)) > 0) throw failure("INVALID_SETTLEMENT");
            write(ResearchAgentExperiment.object("event", "settle", "requestId", id, "amountCny", amount.toPlainString(),
                    "time", Instant.now().toString()));
        }

        private void write(JsonObject event) throws Exception {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(ResearchAgentExperiment.JSON.toJson(event) + "\n");
            channel.position(channel.size());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
            apply(event);
        }

        private void apply(JsonObject event) {
            String id = ResearchAgentExperiment.requiredString(event, "requestId", 100);
            BigDecimal amount = new BigDecimal(ResearchAgentExperiment.requiredString(event, "amountCny", 40));
            if (amount.signum() < 0) throw failure("INVALID_LEDGER");
            if ("reserve".equals(ResearchAgentExperiment.string(event, "event"))) {
                if (held.putIfAbsent(id, amount) != null || amount.compareTo(RESERVE) > 0) throw failure("INVALID_LEDGER");
            } else if ("settle".equals(ResearchAgentExperiment.string(event, "event"))) {
                BigDecimal reserved = held.remove(id);
                if (reserved == null || amount.compareTo(reserved) > 0) throw failure("INVALID_LEDGER");
                spent = spent.add(amount);
            } else throw failure("INVALID_LEDGER");
            if (total().compareTo(LIMIT_CNY) > 0) throw failure("INVALID_LEDGER");
        }

        public void close() throws Exception {
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
