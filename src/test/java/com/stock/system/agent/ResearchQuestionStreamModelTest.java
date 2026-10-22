package com.stock.system.agent;

import com.stock.system.agent.service.*;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** 实际框架 WebClient → 随机回环 HTTP SSE；无外部模型、数据库或真实密钥。 */
class ResearchQuestionStreamModelTest {
    private static final String BURST_TEXT = java.util.stream.IntStream.range(0, 75)
            .mapToObj(index -> "第" + index + "项。").collect(java.util.stream.Collectors.joining());
    private static final String BURST_BODY = "{\"overview\":{\"text\":\"" + BURST_TEXT
            + "\",\"sourceIds\":[\"op-1\"]},\"changes\":[],\"members\":[],\"pendingItems\":[]}";
    private final JsonMapper json = JsonMapper.builder().build();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeepSeekChatAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class, SpringAiRetryAutoConfiguration.class, ChatClientAutoConfiguration.class))
            .withUserConfiguration(ResearchAiConfiguration.class, ResearchQuestionModel.class, ResearchSummaryModel.class);

    @ParameterizedTest @ValueSource(ints = {1, 20, 100})
    void burstNativeDeltasKeepTheirOrderWhenTheConsumerBrieflyPauses(int pauseAt) throws Exception {
        try (var provider = new Provider("summary-burst")) {
            enabled(provider).run(context -> {
                var pieces = new ArrayList<String>();
                try (var execution = new ResearchModelExecution()) {
                    var result = context.getBean(ResearchQuestionModel.class).stream(List.of(new SystemMessage("JSON")), List.of(), delta -> {
                        pieces.add(delta);
                        if (pieces.size() == pauseAt) {
                            try { Thread.sleep(80); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                        }
                    });
                    assertThat(result.getText()).isEqualTo(BURST_BODY);
                    assertThat(execution.usageKnown()).isTrue();
                    assertThat(execution.modelCalls).isEqualTo(1);
                }
            });
        }
    }

    @Test void oversizedReceivedPayloadIsRejectedWhileTheKnownUsageStillSettles() throws Exception {
        try (var provider = new Provider("payload-too-large")) {
            enabled(provider).run(context -> {
                try (var execution = new ResearchModelExecution()) {
                    assertThatThrownBy(() -> context.getBean(ResearchQuestionModel.class).stream(
                            List.of(new SystemMessage("JSON")), List.of(), ignored -> {}))
                            .isInstanceOfSatisfying(BusinessException.class,
                                    failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_INVALID_RESULT));
                    assertThat(execution.modelCalls).isEqualTo(1);
                    assertThat(execution.usageKnown()).isTrue();
                }
                assertThat(provider.calls).hasValue(1);
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"summary", "summary-fence", "summary-bom"})
    void summaryUsesTheSameNativeTransportAndSettlesOneKnownUsage(String mode) throws Exception {
        try (var provider = new Provider(mode)) {
            enabled(provider).run(context -> {
                var pieces = new ArrayList<String>();
                try (var execution = new ResearchModelExecution()) {
                    var input = new ResearchSummaryPrompt(json).build(ResearchSummaryFixtures.sources());
                    var result = context.getBean(ResearchSummaryModel.class).stream(input, pieces::add);
                    assertThat(result.summary().overview().text()).isEqualTo("中文观点。");
                    assertThat(pieces).hasSize(2);
                    assertThat(execution.modelCalls).isEqualTo(1);
                    assertThat(execution.inputTokens).isEqualTo(100);
                    assertThat(execution.usageKnown()).isTrue();
                }
                assertThat(provider.calls).hasValue(1);
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"summary-invalid", "summary-bad-finish"})
    void anInvalidSummaryCannotSaveItsPreviewButStillSettlesKnownUsage(String mode) throws Exception {
        try (var provider = new Provider(mode)) {
            enabled(provider).run(context -> {
                try (var execution = new ResearchModelExecution()) {
                    var input = new ResearchSummaryPrompt(json).build(ResearchSummaryFixtures.sources());
                    assertThatThrownBy(() -> context.getBean(ResearchSummaryModel.class).stream(input, ignored -> {}))
                            .isInstanceOf(BusinessException.class);
                    assertThat(execution.usageKnown()).isTrue();
                    assertThat(execution.modelCalls).isEqualTo(1);
                    assertThat(execution.failure.stage()).startsWith("SUMMARY_");
                }
                assertThat(provider.calls).hasValue(1);
            });
        }
    }

    @Test void disabledModelDoesNotRequireAnInjectedChatModel() {
        runner.withPropertyValues("spring.ai.model.chat=none").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ChatModel.class).doesNotHaveBean(ResearchQuestionModel.class);
        });
    }

    @Test void streamsRealChunksAndSettlesUsageOnceOnTheOriginalExecution() throws Exception {
        try (var provider = new Provider("valid")) {
            enabled(provider).run(context -> {
                var pieces = new ArrayList<String>();
                try (var execution = new ResearchModelExecution()) {
                    var result = context.getBean(ResearchQuestionModel.class).stream(List.of(new SystemMessage("JSON")), List.of(), pieces::add);
                    assertThat(result.getText()).isEqualTo("中文正文");
                    assertThat(pieces).containsExactly("中文", "正文");
                    assertThat(execution.modelCalls).isEqualTo(1);
                    assertThat(execution.inputTokens).isEqualTo(100);
                    assertThat(execution.outputTokens).isEqualTo(80);
                    assertThat(execution.upstreamStatus()).isEqualTo(200);
                    assertThat(execution.usageKnown()).isTrue();
                }
                assertThat(provider.calls.get()).isEqualTo(1);
            });
        }
    }

    @Test void aPreviewCancellationStillDrainsThePaidRequestAndRetainsKnownUsage() throws Exception {
        try (var provider = new Provider("valid")) {
            enabled(provider).run(context -> {
                try (var execution = new ResearchModelExecution()) {
                    assertThatThrownBy(() -> context.getBean(ResearchQuestionModel.class).stream(List.of(new SystemMessage("JSON")), List.of(),
                            ignored -> { throw new BusinessException(ErrorCode.RESEARCH_AGENT_CANCELLED); }))
                            .isInstanceOfSatisfying(BusinessException.class,
                                    failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_CANCELLED));
                    assertThat(execution.modelCalls).isEqualTo(1);
                    assertThat(execution.inputTokens).isEqualTo(100);
                    assertThat(execution.usageKnown()).isTrue();
                }
                assertThat(provider.calls.get()).isEqualTo(1);
            });
        }
    }

    @ParameterizedTest @ValueSource(strings = {"no-finish", "bad-finish"})
    void incompleteStreamsDoNotBecomeSuccessfulAnswersEvenWhenUsageIsKnown(String mode) throws Exception {
        try (var provider = new Provider(mode)) {
            enabled(provider).run(context -> {
                try (var execution = new ResearchModelExecution()) {
                    assertThatThrownBy(() -> context.getBean(ResearchQuestionModel.class).stream(List.of(new SystemMessage("JSON")), List.of(), ignored -> {}))
                            .isInstanceOfSatisfying(BusinessException.class,
                                    failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_INVALID_RESULT));
                    assertThat(execution.modelCalls).isEqualTo(1);
                    assertThat(execution.usageKnown()).isTrue();
                }
                assertThat(provider.calls.get()).isEqualTo(1);
            });
        }
    }

    @Test void missingUsageRetainsAnUnknownReservationRatherThanInventingZeroCost() throws Exception {
        try (var provider = new Provider("no-usage")) {
            enabled(provider).run(context -> {
                try (var execution = new ResearchModelExecution()) {
                    context.getBean(ResearchQuestionModel.class).stream(List.of(new SystemMessage("JSON")), List.of(), ignored -> {});
                    assertThat(execution.modelCalls).isEqualTo(1);
                    assertThat(execution.usageKnown()).isFalse();
                }
            });
        }
    }

    @Test void aRealHttpFailureHasSafeStatusAndOnlyOnePhysicalDispatch() throws Exception {
        try (var provider = new Provider("failure")) {
            enabled(provider).run(context -> {
                try (var execution = new ResearchModelExecution()) {
                    assertThatThrownBy(() -> context.getBean(ResearchQuestionModel.class).stream(List.of(new SystemMessage("JSON")), List.of(), ignored -> {}))
                            .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getMessage()).doesNotContain("provider-secret-error"));
                    assertThat(execution.modelCalls).isEqualTo(1);
                    assertThat(execution.failure.upstreamStatus()).isEqualTo(503);
                    assertThat(execution.usageKnown()).isFalse();
                }
                assertThat(provider.calls.get()).isEqualTo(1);
            });
        }
    }

    @Test void encodedBodySizeIsCheckedBeforeTheModelCallIsCounted() throws Exception {
        try (var provider = new Provider("valid")) {
            enabled(provider).run(context -> {
                try (var execution = new ResearchModelExecution()) {
                    assertThatThrownBy(() -> context.getBean(ResearchQuestionModel.class).stream(
                            List.of(new SystemMessage("JSON 中".repeat(15_000))), List.of(), ignored -> {})).isInstanceOf(BusinessException.class);
                    assertThat(execution.modelCalls).isZero();
                    assertThat(execution.dispatched).isFalse();
                }
            });
        }
    }

    private ApplicationContextRunner enabled(Provider provider) {
        return runner.withPropertyValues("spring.ai.model.chat=deepseek", "spring.ai.deepseek.api-key=offline-stream-key",
                "spring.ai.deepseek.base-url=http://127.0.0.1:" + provider.server.getAddress().getPort());
    }

    private final class Provider implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
        private final AtomicInteger calls = new AtomicInteger();
        Provider(String mode) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/chat/completions", exchange -> {
                try (exchange) {
                    var request = json.readTree(exchange.getRequestBody().readAllBytes());
                    if (!request.path("stream").asBoolean()) throw new IllegalStateException("Native stream was not requested");
                    calls.incrementAndGet();
                    if (mode.equals("failure")) {
                        exchange.sendResponseHeaders(503, 0);
                        exchange.getResponseBody().write("provider-secret-error".getBytes(StandardCharsets.UTF_8)); return;
                    }
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    var output = exchange.getResponseBody();
                    String prefix = mode.equals("summary-fence") ? "```json\n" : mode.equals("summary-bom") ? "\uFEFF" : "";
                    var deltas = mode.startsWith("summary") ? List.of(
                            prefix + "{\"overview\":{\"text\":\"中文观点。\",\"sourceIds\":[\"op-1\"]},",
                            mode.equals("summary-invalid") ? "bad-json" : "\"changes\":[],\"members\":[],\"pendingItems\":[]}" + (mode.equals("summary-fence") ? "\n```" : ""), "")
                            : List.of("中文", "正文", "");
                    if (mode.equals("summary-burst")) deltas = java.util.stream.Stream.concat(
                            BURST_BODY.chars().mapToObj(value -> Character.toString((char)value)), java.util.stream.Stream.of("")).toList();
                    if (mode.equals("payload-too-large")) deltas = List.of("x".repeat(40_000), "y".repeat(40_000), "");
                    for (String text : deltas) {
                        var choice = new java.util.LinkedHashMap<String, Object>();
                        choice.put("index", 0); choice.put("delta", Map.of("role", "assistant", "content", text));
                        choice.put("finish_reason", !text.isEmpty() || mode.equals("no-finish") ? null : mode.endsWith("bad-finish") ? "length" : "stop");
                        var frame = new java.util.LinkedHashMap<String, Object>();
                        frame.put("id", "offline-stream"); frame.put("object", "chat.completion.chunk");
                        frame.put("created", 1); frame.put("model", "offline-stream-model"); frame.put("choices", List.of(choice));
                        if (text.isEmpty() && !mode.equals("no-usage")) frame.put("usage", Map.of("prompt_tokens", 100, "completion_tokens", 80, "total_tokens", 180));
                        output.write(("data: " + json.writeValueAsString(frame) + "\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
                    }
                    output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (java.io.IOException disconnected) { /* 请求体被守卫拒绝时没有模型派发。 */ }
            });
            server.start();
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
