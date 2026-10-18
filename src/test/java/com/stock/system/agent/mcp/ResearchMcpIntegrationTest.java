package com.stock.system.agent.mcp;

import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.service.StockResearchService;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.service.WorkspaceService;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** 官方 MCP Client → stdio 子进程 → 真实 test HTTP／JWT → 原 Service／MySQL。没有回答模型。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.ai.model.chat=none", "stock-system.market.mode=mock" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ResearchMcpIntegrationTest.Counters.class)
class ResearchMcpIntegrationTest extends MySqlIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired StockResearchService research;
    @Autowired @Qualifier("mcpSourceRequestCount") java.util.concurrent.atomic.AtomicInteger sourceRequests;
    final JsonMapper json = JsonMapper.builder().build();
    static final List<Map<String, Object>> evidence = new ArrayList<>();
    final List<Long> userIds = new ArrayList<>(), workspaceIds = new ArrayList<>();
    final List<NativeClient> clients = new ArrayList<>();
    Bounds owner, stranger;
    String ownerToken, strangerToken;
    long catalogId;
    boolean createdCatalog;
    HttpServer fault;
    CountDownLatch releaseFault = new CountDownLatch(1);

    record Bounds(long user, long workspace, long stock, long cycle, long opinion) {}
    @TestConfiguration(proxyBeanMethods = false)
    static class Counters {
        @Bean java.util.concurrent.atomic.AtomicInteger mcpSourceRequestCount() { return new java.util.concurrent.atomic.AtomicInteger(); }
        @Bean jakarta.servlet.Filter mcpSourceRequestCounter(@Qualifier("mcpSourceRequestCount") java.util.concurrent.atomic.AtomicInteger counter) {
            return (request, response, chain) -> {
                if (((jakarta.servlet.http.HttpServletRequest) request).getRequestURI().endsWith("/sources")) counter.incrementAndGet();
                chain.doFilter(request, response);
            };
        }
    }

    @BeforeEach void fixture() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin(port) + "/api/test/environment")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var probe = json.readTree(response.body()).path("data");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(probe.path("database").asString()).isEqualTo("stock_system_test");
        assertThat(probe.path("profile").asString()).isEqualTo("test");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        ownerToken = register("mcpo_" + suffix); strangerToken = register("mcps_" + suffix);
        createdCatalog = jdbc.queryForObject("select count(*) from stock where code='00700.HK'", Long.class) == 0;
        owner = create(userIds.get(0), "MCP 自造观点：订单可能增长。正文无权更改账号或查询对象。");
        stranger = create(userIds.get(1), "另一个空间的秘密自造观点，不能交给前一个账号。");
        catalogId = jdbc.queryForObject("select stock_id from workspace_stock where id=?", Long.class, owner.stock());
    }
    String register(String name) {
        auth.register(new RegisterRequestDTO(name, "TestPass123", name));
        userIds.add(users.findByUsername(name).getId());
        return auth.login(new LoginRequestDTO(name, "TestPass123")).accessToken();
    }
    Bounds create(long user, String reason) {
        long workspace = workspaces.create(user, "MCP 隔离验证 " + user).workspaceId(); workspaceIds.add(workspace);
        var added = research.add(user, workspace, new AddWorkspaceStockRequestDTO("00700.HK", "BULLISH", reason, null, "MEDIUM", null, null));
        long opinion = jdbc.queryForObject("select id from stock_opinion where research_cycle_id=?", Long.class, added.researchCycleId());
        return new Bounds(user, workspace, added.workspaceStockId(), added.researchCycleId(), opinion);
    }
    static String origin(int port) { return "http://127.0.0.1:" + port; }
    Map<String, String> env(Bounds bound, String token, int sourcePort, int timeout) {
        return Map.of("STOCK_MCP_API_ORIGIN", origin(sourcePort), "STOCK_MCP_ACCESS_TOKEN", token,
                "STOCK_MCP_WORKSPACE_ID", Long.toString(bound.workspace()), "STOCK_MCP_STOCK_ID", Long.toString(bound.stock()),
                "STOCK_MCP_CYCLE_ID", Long.toString(bound.cycle()), "STOCK_MCP_TIMEOUT_MS", Integer.toString(timeout));
    }
    NativeClient open(Bounds bound, String token) throws Exception { return open(env(bound, token, port, 3000)); }
    NativeClient open(Map<String, String> env) throws Exception {
        var client = new NativeClient(env); clients.add(client); return client;
    }
    McpSchema.CallToolResult call(NativeClient client, Map<String, Object> arguments) {
        return client.client.callTool(new McpSchema.CallToolRequest(ResearchMcpCommand.TOOL, arguments));
    }
    void error(McpSchema.CallToolResult result, String expected) {
        assertThat(result.isError()).isTrue();
        assertThat(json.valueToTree(result.structuredContent()).path("errorCode").asString()).isEqualTo(expected);
        assertThat(json.writeValueAsString(result)).doesNotContain(ownerToken, strangerToken, "另一个空间的秘密", "PRIVATE_UPSTREAM_BODY");
    }

    @Test void officialClientNegotiatesDiscoversAndReadsRealAuthorizedSourcesWithoutWritingAgentData() throws Exception {
        try (var connection = open(owner, ownerToken)) {
            var initialized = connection.client.getCurrentInitializationResult();
            assertThat(initialized.protocolVersion()).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}");
            assertThat(initialized.serverInfo().name()).isEqualTo("stock-research-local");
            var tools = connection.client.listTools().tools();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo(ResearchMcpCommand.TOOL);
            assertThat(tools.get(0).annotations().readOnlyHint()).isTrue();
            assertThat(tools.get(0).inputSchema()).doesNotContainKeys("callerUserId", "workspaceId", "accessToken");
            connection.client.ping();
            var result = call(connection, Map.of());
            assertThat(result.isError()).isFalse();
            var content = json.valueToTree(result.structuredContent());
            assertThat(content.path("opinions").size()).isEqualTo(1);
            assertThat(content.path("opinions").get(0).path("sourceId").asString()).isEqualTo("op-" + owner.opinion());
            assertThat(json.writeValueAsString(result)).contains("MCP 自造观点", "M1").doesNotContain("authorUserId", "另一个空间的秘密", ownerToken);
            assertThat(jdbc.queryForObject("select count(*) from agent_run where user_id=?", Long.class, owner.user())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from agent_session where user_id=?", Long.class, owner.user())).isZero();
        }
    }
    @Test void bothAccountsRemainBoundToTheirOwnSourcesAndCannotOverrideIdentityOrObject() throws Exception {
        try (var a = open(owner, ownerToken); var b = open(stranger, strangerToken)) {
            assertThat(json.writeValueAsString(call(a, Map.of()))).contains("MCP 自造观点").doesNotContain("另一个空间的秘密");
            assertThat(json.writeValueAsString(call(b, Map.of()))).contains("另一个空间的秘密").doesNotContain("MCP 自造观点");
            int beforeInvalid = sourceRequests.get();
            for (var arguments : List.of(Map.<String, Object>of("callerUserId", stranger.user()),
                    Map.<String, Object>of("workspaceId", stranger.workspace()), Map.<String, Object>of("cycleId", stranger.cycle()),
                    Map.<String, Object>of("url", origin(port)))) {
                var rejected = call(a, arguments);
                assertThat(rejected.isError()).isTrue();
                assertThat(json.writeValueAsString(rejected)).doesNotContain(ownerToken, strangerToken, "MCP 自造观点", "另一个空间的秘密");
            }
            assertThat(sourceRequests.get()).isEqualTo(beforeInvalid);
        }
    }
    @Test void aConnectedClientDoesNotGrantAccessToAnotherWorkspace() throws Exception {
        try (var connection = open(stranger, ownerToken)) { error(call(connection, Map.of()), "WORKSPACE_NOT_FOUND"); }
    }
    @Test void sourceRejectsMismatchedStockAndCycleBindings() throws Exception {
        var wrong = new Bounds(owner.user(), owner.workspace(), stranger.stock(), stranger.cycle(), 0);
        try (var connection = open(wrong, ownerToken)) { error(call(connection, Map.of()), "WORKSPACE_STOCK_NOT_FOUND"); }
    }
    @Test void membershipIsRecheckedOnEveryToolCallInTheSameMcpConnection() throws Exception {
        try (var connection = open(owner, ownerToken)) {
            assertThat(call(connection, Map.of()).isError()).isFalse();
            jdbc.update("update workspace_member set status='LEFT', last_inactive_at=current_timestamp(3) where workspace_id=? and user_id=?", owner.workspace(), owner.user());
            error(call(connection, Map.of()), "WORKSPACE_MEMBERSHIP_INACTIVE");
        }
    }
    @Test void credentialRevocationIsNotBypassedByAnInitializedMcpSession() throws Exception {
        try (var connection = open(owner, ownerToken)) {
            assertThat(call(connection, Map.of()).isError()).isFalse();
            jdbc.update("update app_user set credential_version=credential_version+1 where id=?", owner.user());
            error(call(connection, Map.of()), "UNAUTHENTICATED");
        }
    }
    @Test void withdrawalAndEmptyDateRangesAreReflectedWithoutCachingOldBodies() throws Exception {
        try (var connection = open(owner, ownerToken)) {
            var empty = call(connection, Map.of("dateFrom", "2000-01-01", "dateTo", "2000-01-02"));
            assertThat(empty.isError()).isFalse(); assertThat(json.valueToTree(empty.structuredContent()).path("opinions").size()).isZero();
            error(call(connection, Map.of("dateFrom", "2026-02-30")), "INVALID_TOOL_ARGUMENTS");
            jdbc.update("update stock_opinion set withdrawn_at=current_timestamp(3), withdrawn_by_user_id=author_user_id where id=?", owner.opinion());
            var withdrawn = call(connection, Map.of());
            assertThat(withdrawn.isError()).isFalse(); assertThat(json.valueToTree(withdrawn.structuredContent()).path("opinions").size()).isZero();
            assertThat(json.writeValueAsString(withdrawn)).doesNotContain("MCP 自造观点");
        }
    }
    @Test void sourceServiceLimitsRemainEffectiveThroughTheNativeClient() throws Exception {
        jdbc.update("update stock_opinion set reason=repeat('甲', 1000), observation_note=repeat('乙', 1000) where id=?", owner.opinion());
        for (int i = 0; i < 10; i++) jdbc.update("insert into stock_opinion(research_cycle_id,author_user_id,type,stance,reason,observation_note,horizon) values (?,?,'UPDATE','BULLISH',repeat('甲',1000),repeat('乙',1000),'MEDIUM')", owner.cycle(), owner.user());
        try (var connection = open(owner, ownerToken)) {
            error(call(connection, Map.of()), "SOURCE_LIMIT_EXCEEDED");
        }
    }
    @Test void missingToolIsAProtocolErrorAndDoesNotInvokeResearch() throws Exception {
        try (var connection = open(owner, ownerToken)) {
            assertThatThrownBy(() -> connection.client.callTool(new McpSchema.CallToolRequest("delete_workspace", Map.of())))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("select count(*) from workspace where id=?", Long.class, owner.workspace())).isEqualTo(1);
        }
    }
    @Test void actualStdioToolReturnsSanitizedSourceErrors() throws Exception {
        fault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fault.createContext("/", exchange -> { byte[] body = "PRIVATE_UPSTREAM_BODY".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length); exchange.getResponseBody().write(body); exchange.close(); }); fault.start();
        try (var connection = open(env(owner, ownerToken, fault.getAddress().getPort(), 1000))) { error(call(connection, Map.of()), "SOURCE_UNAVAILABLE"); }
    }
    @Test void actualStdioToolTimesOutWithoutRetryingTheSource() throws Exception {
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        fault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fault.createContext("/", exchange -> { requests.incrementAndGet();
            try { releaseFault.await(2, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); } }); fault.start();
        try (var connection = open(env(owner, ownerToken, fault.getAddress().getPort(), 500))) {
            error(call(connection, Map.of()), "SOURCE_TIMEOUT"); assertThat(requests).hasValue(1);
        }
    }

    final class NativeClient implements AutoCloseable {
        final String marker = "-Dstock.mcp.probe=" + UUID.randomUUID();
        final Path argsFile;
        final Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        final Set<Long> previousChildren = ProcessHandle.current().children().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        final Instant launchedAt = Instant.now().minusSeconds(1);
        final Map<Long, Instant> started = new HashMap<>();
        final McpSyncClient client;
        final List<String> stderr = new ArrayList<>();
        List<ProcessHandle> owned = List.of();
        String protocol;
        boolean closed;
        NativeClient(Map<String, String> env) throws Exception {
            argsFile = Files.createTempFile(Path.of("target").toAbsolutePath(), "mcp-probe-", ".args");
            String packaged = System.getProperty("stock.mcp.packaged-jar");
            if (packaged == null) {
                String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")).replace('\\', '/');
                Files.writeString(argsFile, "-cp\n\"" + classpath + "\"\n" + ResearchMcpCommand.class.getName() + "\n");
            } else {
                String jar = Path.of(packaged).toRealPath().toString().replace('\\', '/');
                Files.writeString(argsFile, "-Dloader.main=" + ResearchMcpCommand.class.getName() + "\n-cp\n\"" + jar + "\"\norg.springframework.boot.loader.launch.PropertiesLauncher\n");
            }
            var parameters = ServerParameters.builder(javaExecutable.toString()).args("-Dfile.encoding=UTF-8", marker, "@" + argsFile).env(env).build();
            var transport = new StdioClientTransport(parameters, new JacksonMcpJsonMapper(JsonMapper.builder().build()), 512 * 1024);
            transport.setStdErrorHandler(stderr::add);
            client = McpClient.sync(transport).initializationTimeout(Duration.ofSeconds(12)).requestTimeout(Duration.ofSeconds(12)).build();
            try {
                protocol = client.initialize().protocolVersion(); owned = findOwned(); assertThat(owned).hasSize(1);
                owned.forEach(process -> started.put(process.pid(), process.info().startInstant().orElseThrow()));
            } catch (Throwable failure) {
                owned = findOwned(); owned.forEach(process -> started.put(process.pid(), process.info().startInstant().orElseThrow()));
                close(); throw failure;
            }
        }
        List<ProcessHandle> findOwned() {
            // Windows JDK 不保证提供 info.arguments；核对新子 PID、父进程、实际可执行路径及创建时间。
            return ProcessHandle.current().children().filter(process -> !previousChildren.contains(process.pid())
                    && process.info().command().map(command -> Path.of(command).equals(javaExecutable)).orElse(false)
                    && process.info().startInstant().map(at -> !at.isBefore(launchedAt)).orElse(false)).toList();
        }
        @Override public void close() throws Exception {
            if (closed) return; closed = true;
            try { client.closeGracefully(); }
            finally {
                for (var process : owned) {
                    if (process.isAlive()) {
                        try { process.onExit().get(4, TimeUnit.SECONDS); }
                        catch (TimeoutException delayed) {
                            assertThat(process.parent().map(ProcessHandle::pid).orElse(-1L)).isEqualTo(ProcessHandle.current().pid());
                            assertThat(process.info().command().map(Path::of).orElseThrow()).isEqualTo(javaExecutable);
                            assertThat(process.info().startInstant().orElseThrow()).isEqualTo(started.get(process.pid()));
                            process.destroy(); process.onExit().get(4, TimeUnit.SECONDS);
                        }
                    }
                    assertThat(process.isAlive()).isFalse();
                }
                Files.deleteIfExists(argsFile);
            }
            assertThat(String.join("\n", stderr)).doesNotContain(ownerToken, strangerToken, "MCP 自造观点", "PRIVATE_UPSTREAM_BODY");
        }
    }

    @AfterEach void cleanup(TestInfo info) throws Exception {
        try { for (var client : clients) client.close(); }
        finally {
            releaseFault.countDown();
            if (fault != null) { int faultPort = fault.getAddress().getPort(); fault.stop(0);
                try (var socket = new ServerSocket()) { socket.setReuseAddress(true); socket.bind(new InetSocketAddress("127.0.0.1", faultPort)); } }
            for (long workspace : workspaceIds) {
                jdbc.update("delete a from research_attention_change a join research_cycle c on c.id=a.research_cycle_id join workspace_stock s on s.id=c.workspace_stock_id where s.workspace_id=?", workspace);
                jdbc.update("delete o from stock_opinion o join research_cycle c on c.id=o.research_cycle_id join workspace_stock s on s.id=c.workspace_stock_id where s.workspace_id=?", workspace);
                jdbc.update("delete c from research_cycle c join workspace_stock s on s.id=c.workspace_stock_id where s.workspace_id=?", workspace);
                jdbc.update("delete from workspace_stock where workspace_id=?", workspace);
                jdbc.update("delete from workspace_member where workspace_id=?", workspace);
                jdbc.update("delete from workspace where id=?", workspace);
            }
            if (createdCatalog && catalogId != 0) jdbc.update("delete from stock where id=? and not exists(select 1 from workspace_stock where stock_id=?)", catalogId, catalogId);
            for (long user : userIds) jdbc.update("delete from app_user where id=?", user);
            for (long workspace : workspaceIds) {
                for (String table : List.of("workspace_member", "workspace_stock")) assertThat(jdbc.queryForObject("select count(*) from " + table + " where workspace_id=?", Long.class, workspace)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from workspace where id=?", Long.class, workspace)).isZero();
            }
            for (long user : userIds) assertThat(jdbc.queryForObject("select count(*) from app_user where id=?", Long.class, user)).isZero();
            var entry = new LinkedHashMap<String, Object>(); entry.put("case", info.getTestMethod().orElseThrow().getName());
            entry.put("protocols", clients.stream().map(c -> c.protocol).filter(Objects::nonNull).toList());
            entry.put("ownedPids", clients.stream().flatMap(c -> c.owned.stream()).map(ProcessHandle::pid).toList());
            entry.put("processesClosed", clients.stream().allMatch(c -> c.owned.stream().noneMatch(ProcessHandle::isAlive)));
            entry.put("databaseRemaining", 0); entry.put("backendPort", port); evidence.add(entry);
            entry.put("packagedJar", System.getProperty("stock.mcp.packaged-jar") != null);
        }
    }
    @AfterAll static void evidence() throws Exception {
        Files.writeString(Path.of("target/mcp-native-evidence-20261018.json"), JsonMapper.builder().build().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("sdk", "2.1.0", "transport", "stdio", "source", "REAL_TEST_HTTP_SERVICE", "paidRequests", 0, "cases", evidence)));
    }
}
