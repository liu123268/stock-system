package com.stock.system.stock.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongbridgeJsonRpcClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void correlatesOutOfOrderResponsesAndParsesQuotes() throws Exception {
        FakeProcess fakeProcess = new FakeProcess(server -> {
            JsonNode first = server.readRequest();
            JsonNode second = server.readRequest();
            server.respondForRequest(second);
            server.respondForRequest(first);
        });

        try (LongbridgeJsonRpcClient client = client(() -> fakeProcess, Duration.ofSeconds(2))) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Map<String, LongbridgeQuote>> first = executor.submit(
                        () -> client.getQuotes(List.of("600519.SH")));
                Future<Map<String, LongbridgeQuote>> second = executor.submit(
                        () -> client.getQuotes(List.of("NVDA.US")));

                assertThat(first.get(2, TimeUnit.SECONDS).get("600519.SH").lastDone())
                        .isEqualByComparingTo("1290.8800");
                assertThat(second.get(2, TimeUnit.SECONDS).get("NVDA.US").lastDone())
                        .isEqualByComparingTo("184.2500");
            } finally {
                executor.shutdownNow();
            }
        }
        assertThat(fakeProcess.isAlive()).isFalse();
    }

    @Test
    void sendsOneBatchAndUsesShortLivedCache() throws Exception {
        FakeProcess fakeProcess = new FakeProcess(server -> {
            JsonNode request = server.readRequest();
            assertThat(request.path("method").asText()).isEqualTo("quote.quote");
            assertThat(request.path("params").path("symbols")).hasSize(2);
            long id = request.path("id").asLong();
            server.respond(id, List.of(
                    quoteJson("600519.SH", "1290.88", "2026-09-09T07:00:00Z"),
                    quoteJson("00700.HK", "612.00", "2026-09-09T06:59:00Z")
            ));
        });

        try (LongbridgeJsonRpcClient client = client(() -> fakeProcess, Duration.ofSeconds(2))) {
            List<String> symbols = List.of("600519.SH", "00700.HK");
            assertThat(client.getQuotes(symbols)).hasSize(2);
            assertThat(client.getQuotes(symbols)).hasSize(2);
            assertThat(fakeProcess.receivedRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void requestsCandlesticksWithExplicitPeriodAndUsesItsOwnCache() throws Exception {
        FakeProcess fakeProcess = new FakeProcess(server -> {
            JsonNode request = server.readRequest();
            assertThat(request.path("method").asText()).isEqualTo("quote.candlesticks");
            assertThat(request.path("params").path("symbol").asText()).isEqualTo("600519.SH");
            assertThat(request.path("params").path("period").asText()).isEqualTo("week");
            assertThat(request.path("params").path("count").asInt()).isEqualTo(120);
            assertThat(request.path("params").path("adjust").asText()).isEqualTo("none");
            server.respond(request.path("id").asLong(), List.of(
                    candlestickJson("2026-09-04T07:00:00Z", "1290", "1305", "1280", "1298")
            ));
        });

        try (LongbridgeJsonRpcClient client = client(() -> fakeProcess, Duration.ofSeconds(2))) {
            List<LongbridgeCandlestick> first = client.getCandlesticks(
                    "600519.sh", CandlestickPeriod.WEEK, 120);
            List<LongbridgeCandlestick> second = client.getCandlesticks(
                    "600519.SH", CandlestickPeriod.WEEK, 120);

            assertThat(first).hasSize(1);
            assertThat(first.get(0).close()).isEqualByComparingTo("1298");
            assertThat(second).isEqualTo(first);
            assertThat(fakeProcess.receivedRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void preservesLongbridgeBusinessCodeForInterfaceLimitErrors() throws Exception {
        FakeProcess fakeProcess = new FakeProcess(server -> {
            JsonNode request = server.readRequest();
            server.respondError(
                    request.path("id").asLong(), 7, "request failed", "301607");
        });

        try (LongbridgeJsonRpcClient client = client(() -> fakeProcess, Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> client.getCandlesticks(
                    "600519.SH", CandlestickPeriod.DAY, 120))
                    .isInstanceOfSatisfying(LongbridgeCliException.class,
                            exception -> assertThat(exception.hasProviderCode("301607")).isTrue());
        }
    }

    @Test
    void requestsHistoricalCandlesticksByMarketDateForLeftEdgeLoading() throws Exception {
        FakeProcess fakeProcess = new FakeProcess(server -> {
            JsonNode request = server.readRequest();
            assertThat(request.path("method").asText())
                    .isEqualTo("quote.history_candlesticks_by_date");
            assertThat(request.path("params").path("symbol").asText()).isEqualTo("NVDA.US");
            assertThat(request.path("params").path("period").asText()).isEqualTo("1m");
            assertThat(request.path("params").path("end").asText()).isEqualTo("2026-09-08");
            assertThat(request.path("params").path("adjust").asText()).isEqualTo("none");
            server.respond(request.path("id").asLong(), List.of(
                    candlestickJson("2026-09-08T19:58:00Z", "100", "101", "99", "100.5")
            ));
        });

        try (LongbridgeJsonRpcClient client = client(() -> fakeProcess, Duration.ofSeconds(2))) {
            assertThat(client.getCandlesticksBefore(
                    "NVDA.US", CandlestickPeriod.MINUTE_1, 1000,
                    Instant.parse("2026-09-08T20:00:00Z"))).hasSize(1);
        }
    }

    @Test
    void requestsTodayIntradayAndUsesShortLivedCache() throws Exception {
        FakeProcess fakeProcess = new FakeProcess(server -> {
            JsonNode request = server.readRequest();
            assertThat(request.path("method").asText()).isEqualTo("quote.intraday");
            assertThat(request.path("params").path("symbol").asText()).isEqualTo("600519.SH");
            server.respond(request.path("id").asLong(), List.of(
                    intradayJson("2026-09-10T01:31:00Z", "1488.88", "1487.50")
            ));
        });

        try (LongbridgeJsonRpcClient client = client(() -> fakeProcess, Duration.ofSeconds(2))) {
            assertThat(client.getIntraday("600519.SH")).hasSize(1);
            assertThat(client.getIntraday("600519.SH")).hasSize(1);
            assertThat(fakeProcess.receivedRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void timesOutWithoutLeavingPendingWork() throws Exception {
        FakeProcess fakeProcess = new FakeProcess(server -> {
            server.readRequest();
            Thread.sleep(1_000);
        });
        try (LongbridgeJsonRpcClient client = client(() -> fakeProcess, Duration.ofMillis(150))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> client.getQuotes(List.of("600519.SH")))
                    .isInstanceOf(LongbridgeCliException.class)
                    .hasMessage("Longbridge request timed out");
        }
        assertThat(fakeProcess.isAlive()).isFalse();
    }

    private LongbridgeJsonRpcClient client(
            LongbridgeJsonRpcClient.ProcessStarter starter,
            Duration timeout
    ) {
        return new LongbridgeJsonRpcClient(
                JSON,
                starter,
                timeout,
                Duration.ofSeconds(5),
                5,
                Duration.ofMillis(100)
        );
    }

    private static String quoteJson(String symbol, String price, String timestamp) {
        return "{\"symbol\":\"" + symbol + "\",\"last_done\":\"" + price
                + "\",\"timestamp\":\"" + timestamp + "\",\"trade_status\":\"Normal\"}";
    }

    private static String candlestickJson(
            String timestamp,
            String open,
            String high,
            String low,
            String close
    ) {
        return "{\"timestamp\":\"" + timestamp + "\",\"open\":\"" + open
                + "\",\"high\":\"" + high + "\",\"low\":\"" + low
                + "\",\"close\":\"" + close
                + "\",\"volume\":123456,\"turnover\":\"159753000\"}";
    }

    private static String intradayJson(String timestamp, String price, String averagePrice) {
        return "{\"timestamp\":\"" + timestamp + "\",\"price\":\"" + price
                + "\",\"avg_price\":\"" + averagePrice
                + "\",\"volume\":1234,\"turnover\":\"1836800\"}";
    }

    @FunctionalInterface
    private interface ServerScript {
        void run(Server server) throws Exception;
    }

    private static final class FakeProcess extends Process {
        private final PipedOutputStream processInput = new PipedOutputStream();
        private final PipedInputStream serverInput;
        private final PipedInputStream processOutput = new PipedInputStream();
        private final PipedOutputStream serverOutput;
        private final Thread serverThread;
        private volatile boolean alive = true;
        private volatile int requestCount;

        private FakeProcess(ServerScript script) throws IOException {
            serverInput = new PipedInputStream(processInput);
            serverOutput = new PipedOutputStream(processOutput);
            Server server = new Server(serverInput, serverOutput, this);
            serverThread = new Thread(() -> {
                try {
                    script.run(server);
                } catch (Exception ignored) {
                    // The client assertion exposes protocol failures; shutdown closes the pipes normally.
                } finally {
                    finish();
                }
            }, "fake-longbridge-server");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        @Override
        public OutputStream getOutputStream() {
            return processInput;
        }

        @Override
        public InputStream getInputStream() {
            return processOutput;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public synchronized int waitFor() throws InterruptedException {
            while (alive) {
                wait();
            }
            return 0;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("still running");
            }
            return 0;
        }

        @Override
        public void destroy() {
            finish();
        }

        @Override
        public Process destroyForcibly() {
            finish();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        private synchronized void finish() {
            if (!alive) {
                return;
            }
            alive = false;
            try {
                serverOutput.close();
            } catch (IOException ignored) {
            }
            try {
                serverInput.close();
            } catch (IOException ignored) {
            }
            notifyAll();
        }

        private int receivedRequestCount() {
            return requestCount;
        }
    }

    private static final class Server {
        private final BufferedReader reader;
        private final OutputStream output;
        private final FakeProcess owner;

        private Server(InputStream input, OutputStream output, FakeProcess owner) {
            this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            this.output = output;
            this.owner = owner;
        }

        private JsonNode readRequest() throws IOException {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("client input closed");
            }
            owner.requestCount++;
            return JSON.readTree(line);
        }

        private void respond(long id, String symbol, String price, String timestamp) throws IOException {
            respond(id, List.of(quoteJson(symbol, price, timestamp)));
        }

        private void respondForRequest(JsonNode request) throws IOException {
            String symbol = request.path("params").path("symbols").path(0).asText();
            if ("NVDA.US".equals(symbol)) {
                respond(request.path("id").asLong(), symbol, "184.2500", "2026-09-09T07:01:00Z");
            } else {
                respond(request.path("id").asLong(), symbol, "1290.8800", "2026-09-09T07:00:00Z");
            }
        }

        private void respond(long id, List<String> quoteJsonItems) throws IOException {
            String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":["
                    + String.join(",", quoteJsonItems) + "]}\n";
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private void respondError(
                long id,
                int rpcCode,
                String message,
                String providerCode
        ) throws IOException {
            String response = "{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"error\":{\"code\":" + rpcCode + ",\"message\":\"" + message
                    + "\",\"data\":{\"code\":\"" + providerCode + "\"}}}\n";
            output.write(response.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}
