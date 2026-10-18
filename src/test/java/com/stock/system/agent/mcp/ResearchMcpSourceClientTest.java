package com.stock.system.agent.mcp;

import com.stock.system.common.api.Result;
import com.stock.system.stock.dto.ResearchSourceFilter;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ResearchMcpSourceClientTest {
    static final String TOKEN = "not-a-real-token.fake-payload-1234567890.fake-signature";
    final JsonMapper json = JsonMapper.builder().build();
    final AtomicInteger requests = new AtomicInteger();
    HttpServer server;
    ExecutorService workers;
    String mode = "valid";
    String query;
    CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
    ResearchMcpSourceClient client;

    @BeforeEach void open() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newFixedThreadPool(4);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); query = exchange.getRequestURI().getRawQuery();
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
            if (mode.equals("wait") || mode.equals("timeout")) {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            }
            int status = mode.equals("failure") ? 503 : mode.equals("redirect") ? 302 : 200;
            if (mode.equals("redirect")) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/credential-trap");
            String text = mode.equals("malformed") ? "not-json PRIVATE_SERVER_DIAGNOSTIC"
                    : mode.equals("oversize") ? "x".repeat(ResearchMcpSourceClient.MAX_HTTP_BYTES + 1)
                    : mode.equals("wrong-target") ? json.writeValueAsString(Result.success(material(99)))
                    : mode.equals("failure") ? "PRIVATE_SERVER_DIAGNOSTIC" : json.writeValueAsString(Result.success(material(2)));
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, mode.equals("oversize") ? 0 : bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (java.io.IOException disconnected) { /* 超时／大小保护会中断读取。 */ }
            finally { exchange.close(); }
        });
        server.start();
        client = new ResearchMcpSourceClient(ResearchMcpSettings.from(environment(server.getAddress().getPort(), 1500)));
    }

    static Map<String, String> environment(int port, int timeout) {
        return new HashMap<>(Map.of("STOCK_MCP_API_ORIGIN", "http://127.0.0.1:" + port,
                "STOCK_MCP_ACCESS_TOKEN", TOKEN, "STOCK_MCP_WORKSPACE_ID", "1", "STOCK_MCP_STOCK_ID", "2",
                "STOCK_MCP_CYCLE_ID", "3", "STOCK_MCP_TIMEOUT_MS", Integer.toString(timeout)));
    }
    ResearchCycleSourcesResponseVO material(long stock) {
        var at = LocalDateTime.of(2026, 10, 18, 12, 0);
        var scope = new ResearchCycleSourcesResponseVO.Scope(at, 1, 8, 1,
                ResearchCycleSourcesResponseVO.Coverage.COMPLETE, 100, 20000, ResearchSourceFilter.fullCycle());
        return new ResearchCycleSourcesResponseVO(stock, "LOCAL.TEST", "测试身份", 3L, "ACTIVE", at, null, scope,
                List.of(new ResearchCycleSourcesResponseVO.OpinionSource(4L, 9123L, "测试成员", "INITIAL", "BULLISH",
                        "仅是成员自造判断", null, "MEDIUM", null, null, at)));
    }
    void error(CallToolResult result, String code) {
        assertThat(result.isError()).isTrue();
        assertThat(json.valueToTree(result.structuredContent()).path("errorCode").asString()).isEqualTo(code);
        assertThat(json.writeValueAsString(result)).doesNotContain(TOKEN, "PRIVATE_SERVER_DIAGNOSTIC");
    }

    @Test void readsOneBoundGetAndReturnsOnlyAllowlistedResearchFields() {
        var result = client.read(Map.of("dateFrom", "2026-01-01", "dateTo", "2026-10-18"));
        assertThat(result.isError()).isFalse();
        assertThat(query).isEqualTo("dateFrom=2026-01-01&dateTo=2026-10-18");
        assertThat(json.writeValueAsString(result)).contains("op-4", "M1", "仅是成员自造判断")
                .contains("\"dateFrom\":\"2026-01-01\"")
                .doesNotContain("authorUserId", "9123", "Authorization", TOKEN);
        assertThat(requests).hasValue(1);
    }
    @ParameterizedTest @ValueSource(strings = {"http://example.com:8082", "https://127.0.0.1:8082", "http://127.0.0.1:8082/api", "http://user:secret@127.0.0.1:8082", "http://127.0.0.1:8082?secret=x"})
    void rejectsOriginsOutsideTheFixedLoopbackBoundary(String origin) {
        var env = environment(8082, 1000); env.put("STOCK_MCP_API_ORIGIN", origin);
        assertThatThrownBy(() -> ResearchMcpSettings.from(env)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MCP_CONFIGURATION_INVALID");
    }
    @Test void rejectsMissingCredentialsAndOutOfRangeBindingsWithoutEchoingSecrets() {
        for (String key : List.of("STOCK_MCP_ACCESS_TOKEN", "STOCK_MCP_WORKSPACE_ID", "STOCK_MCP_STOCK_ID", "STOCK_MCP_CYCLE_ID")) {
            var env = environment(8082, 1000); env.remove(key);
            assertThatThrownBy(() -> ResearchMcpSettings.from(env)).hasMessage("MCP_CONFIGURATION_INVALID");
        }
        var env = environment(8082, 1000); env.put("STOCK_MCP_CYCLE_ID", "9223372036854775808");
        assertThatThrownBy(() -> ResearchMcpSettings.from(env)).hasMessage("MCP_CONFIGURATION_INVALID");
    }
    @Test void rejectsUnknownIdentityScopeAndInvalidDatesBeforeAnyHttpCall() {
        for (var arguments : List.of(Map.<String, Object>of("callerUserId", 1), Map.<String, Object>of("workspaceId", 1),
                Map.<String, Object>of("url", "http://example.com"), Map.<String, Object>of("dateFrom", 3),
                Map.<String, Object>of("dateFrom", "2026-02-30"), Map.<String, Object>of("dateFrom", "2026-10-18", "dateTo", "2026-01-01")))
            error(client.read(arguments), "INVALID_TOOL_ARGUMENTS");
        assertThat(requests).hasValue(0);
    }
    @Test void refusesRedirectsInsteadOfForwardingTheAccountToken() { mode = "redirect"; error(client.read(Map.of()), "SOURCE_REDIRECT_REFUSED"); assertThat(requests).hasValue(1); }
    @Test void sourceFailuresAreSanitizedAndNeverRetried() { mode = "failure"; error(client.read(Map.of()), "SOURCE_UNAVAILABLE"); assertThat(requests).hasValue(1); }
    @Test void malformedSourceBodiesAreNotReturnedAsToolText() { mode = "malformed"; error(client.read(Map.of()), "SOURCE_INVALID_RESPONSE"); }
    @Test void sourceCannotSubstituteAnotherResearchObject() { mode = "wrong-target"; error(client.read(Map.of()), "SOURCE_INVALID_RESPONSE"); }
    @Test void boundsChunkedBodiesBeforeDeserializing() { mode = "oversize"; error(client.read(Map.of()), "SOURCE_LIMIT_EXCEEDED"); assertThat(requests).hasValue(1); }
    @Test void timesOutWholeResponseWithoutRetry() {
        mode = "timeout";
        var shortClient = new ResearchMcpSourceClient(ResearchMcpSettings.from(environment(server.getAddress().getPort(), 500)));
        long at = System.nanoTime(); error(shortClient.read(Map.of()), "SOURCE_TIMEOUT");
        assertThat((System.nanoTime() - at) / 1_000_000).isLessThan(2500); assertThat(requests).hasValue(1);
    }
    @Test void allowsOnlyTwoConcurrentSourceReads() throws Exception {
        mode = "wait";
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> client.read(Map.of())); var second = pool.submit(() -> client.read(Map.of()));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            error(client.read(Map.of()), "SOURCE_BUSY"); assertThat(requests).hasValue(2);
            release.countDown(); assertThat(first.get(2, TimeUnit.SECONDS).isError()).isFalse(); assertThat(second.get(2, TimeUnit.SECONDS).isError()).isFalse();
        } finally { release.countDown(); pool.shutdown(); assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void unicodeEscapedProtocolSurvivesWindowsDefaultCodePageWithoutChangingChineseContent() throws Exception {
        String chinese = "成员自造观点：订单可能增长。需要公告核对。";
        var body = Map.of("text", chinese);
        var gbk = java.nio.charset.Charset.forName("GBK");
        String raw = json.writeValueAsString(body);
        String wronglyDecoded = new String(raw.getBytes(StandardCharsets.UTF_8), gbk);
        assertThat(wronglyDecoded).isNotEqualTo(raw);
        String wire = ResearchMcpCommand.wireJson().writeValueAsString(body);
        assertThat(wire.chars().allMatch(character -> character < 128)).isTrue();
        String compatible = new String(wire.getBytes(StandardCharsets.UTF_8), gbk);
        assertThat(json.readTree(compatible).path("text").asString()).isEqualTo(chinese);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/mcp-encoding-regression.json"), json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("fixture", "SYNTHETIC_CHINESE", "clientCharset", "GBK", "originalText", chinese,
                        "rawUtf8DecodedCorrectly", wronglyDecoded.equals(raw), "escapedJsonDecodedCorrectly", compatible.equals(wire))));
    }
    @AfterEach void close() throws Exception {
        release.countDown(); int port = server.getAddress().getPort(); server.stop(0);
        workers.shutdownNow(); assertThat(workers.awaitTermination(4, TimeUnit.SECONDS)).isTrue();
        try (var socket = new ServerSocket()) { socket.setReuseAddress(true); socket.bind(new InetSocketAddress("127.0.0.1", port)); }
    }
}
