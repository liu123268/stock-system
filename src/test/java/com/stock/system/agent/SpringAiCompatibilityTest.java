package com.stock.system.agent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 主项目框架接入回归：真实适配器连接回环替身，禁止付费调用和真实用户资料。 */
class SpringAiCompatibilityTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeepSeekChatAutoConfiguration.class,
                    ChatClientAutoConfiguration.class, ToolCallingAutoConfiguration.class,
                    SpringAiRetryAutoConfiguration.class))
            .withUserConfiguration(ResearchAiConfiguration.class)
            .withInitializer(context -> {
                context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
                try {
                    context.getEnvironment().getPropertySources().addLast(
                            new ResourcePropertySource(new ClassPathResource("application.properties")));
                } catch (java.io.IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            });

    @Test
    void defaultConfigurationKeepsModelOffWithoutCredentials() {
        runner.withPropertyValues("spring.ai.model.chat=none", "spring.ai.deepseek.api-key=")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DeepSeekChatModel.class));
    }

    @Test
    void enabledAdapterExecutesToolPairsSourceAndConvertsStructuredOutput() throws Exception {
        try (LoopbackProvider provider = new LoopbackProvider(200)) {
            runner.withPropertyValues("spring.ai.model.chat=deepseek",
                            "spring.ai.deepseek.api-key=offline-test-key",
                            "spring.ai.deepseek.base-url=" + provider.baseUrl())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        ProbeTools tools = new ProbeTools();
                        ProbeAnswer answer = context.getBean(ChatClient.Builder.class).build().prompt()
                                .user("读取测试资料，并返回其中的来源编号。")
                                .tools(tools).toolContext(Map.of("boundUser", 11L))
                                .call().entity(ProbeAnswer.class);
                        assertThat(answer.sourceIds()).containsExactly("test-source-1");
                        assertThat(tools.calls).isEqualTo(1);
                        assertThat(provider.requests).hasSize(2);
                        JsonNode first = provider.requests.get(0);
                        assertThat(first.path("model").asString()).isEqualTo("deepseek-flash");
                        assertThat(first.path("thinking").path("type").asString()).isEqualTo("disabled");
                        assertThat(first.path("tools").toString()).contains("read_test_fact")
                                .doesNotContain("boundUser");
                        boolean paired = false;
                        for (JsonNode message : provider.requests.get(1).path("messages")) {
                            if ("tool".equals(message.path("role").asString())) {
                                assertThat(message.path("tool_call_id").asString()).isEqualTo("probe-call-1");
                                assertThat(message.path("content").asString()).contains("test-source-1");
                                paired = true;
                            }
                        }
                        assertThat(paired).isTrue();
                    });
        }
    }

    @Test
    void upstreamFailureDoesNotAutomaticallySendAnotherRequest() throws Exception {
        try (LoopbackProvider provider = new LoopbackProvider(503)) {
            runner.withPropertyValues("spring.ai.model.chat=deepseek",
                            "spring.ai.deepseek.api-key=offline-test-key",
                            "spring.ai.deepseek.base-url=" + provider.baseUrl())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThatThrownBy(() -> context.getBean(DeepSeekChatModel.class).call("测试失败反馈。"))
                                .isInstanceOf(RuntimeException.class);
                        assertThat(provider.requests).hasSize(1);
                    });
        }
    }

    @Test
    void retrievalComponentFiltersStockAndHonorsDocumentRemoval() {
        SimpleVectorStore store = SimpleVectorStore.builder(new ProbeEmbeddingModel()).build();
        store.add(List.of(new Document("owned", "测试段落", Map.of("stock", "sample-a")),
                new Document("other", "测试段落", Map.of("stock", "sample-b"))));
        SearchRequest request = SearchRequest.builder().query("测试查询").topK(2)
                .filterExpression("stock == 'sample-a'").build();
        assertThat(store.similaritySearch(request)).extracting(Document::getId).containsExactly("owned");
        store.delete(List.of("owned"));
        assertThat(store.similaritySearch(request)).isEmpty();
    }

    record ProbeAnswer(List<String> sourceIds) {}

    static class ProbeTools {
        int calls;

        @Tool(name = "read_test_fact", description = "读取本轮回归的自造资料。")
        public Map<String, String> readFact(ToolContext context) {
            if (!Long.valueOf(11L).equals(context.getContext().get("boundUser"))) {
                throw new IllegalArgumentException("PROBE_SCOPE_DENIED");
            }
            calls++;
            return Map.of("sourceId", "test-source-1", "text", "自造测试事实");
        }
    }

    private static class ProbeEmbeddingModel implements EmbeddingModel {
        @Override
        public float[] embed(Document document) { return new float[]{1, 0, 0}; }
        @Override
        public int dimensions() { return 3; }
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> results = new ArrayList<>();
            for (int index = 0; index < request.getInstructions().size(); index++) {
                results.add(new Embedding(new float[]{1, 0, 0}, index));
            }
            return new EmbeddingResponse(results);
        }
    }

    private static class LoopbackProvider implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpServer server;
        private final int port;
        final List<JsonNode> requests = new ArrayList<>();

        LoopbackProvider(int status) throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            port = server.getAddress().getPort();
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    requests.add(mapper.readTree(exchange.getRequestBody()));
                    String response;
                    if (status != 200) {
                        response = "{\"error\":{\"message\":\"offline provider unavailable\"}}";
                    } else {
                        var message = mapper.createObjectNode().put("role", "assistant");
                        if (requests.size() == 1) {
                            message.put("content", "");
                            var tool = message.putArray("tool_calls").addObject()
                                    .put("id", "probe-call-1").put("type", "function");
                            tool.putObject("function").put("name", "read_test_fact").put("arguments", "{}");
                        } else {
                            message.put("content", "{\"sourceIds\":[\"test-source-1\"]}");
                        }
                        var body = mapper.createObjectNode().put("id", "offline-probe")
                                .put("object", "chat.completion").put("created", 1).put("model", "deepseek-flash");
                        var choice = body.putArray("choices").addObject().put("index", 0)
                                .put("finish_reason", requests.size() == 1 ? "tool_calls" : "stop");
                        choice.set("message", message);
                        body.putObject("usage").put("prompt_tokens", 20).put("completion_tokens", 10).put("total_tokens", 30);
                        response = mapper.writeValueAsString(body);
                    }
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
        }

        String baseUrl() { return "http://localhost:" + port; }

        @Override
        public void close() throws Exception {
            server.stop(0);
            try (ServerSocket released = new ServerSocket()) {
                released.setReuseAddress(true);
                released.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            }
        }
    }
}
