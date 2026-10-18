package com.stock.system.agent;

import com.stock.system.agent.service.ResearchModelExecution;
import com.stock.system.agent.service.ResearchQuestionModel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/** Exercises native HTTP serialization, including final answers while tools are offered. */
class ResearchQuestionModelTest {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jsonModeIsAlwaysSentAndToolCallsRemainUnderBusinessControl(boolean toolCall) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://offline.invalid/chat/completions")).andRespond(request -> {
            var sent = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
            assertThat(sent.path("response_format").path("type").asString()).isEqualTo("json_object");
            assertThat(sent.path("tool_choice").asString()).isEqualTo("auto");
            assertThat(sent.path("tools").size()).isEqualTo(1);
            var body = json.createObjectNode().put("id", "offline-question").put("object", "chat.completion")
                    .put("created", 1).put("model", "offline-model");
            var choice = body.putArray("choices").addObject().put("index", 0)
                    .put("finish_reason", toolCall ? "tool_calls" : "stop");
            var message = choice.putObject("message").put("role", "assistant");
            if (toolCall) message.putArray("tool_calls").addObject().put("id", "local-call").put("type", "function")
                    .putObject("function").put("name", "search_report_passages").put("arguments", "{\"query\":\"业务\"}");
            else message.put("content", "{\"paragraphs\":[{\"text\":\"概念说明\",\"sourceIds\":[]}],\"missingInformation\":[]}");
            body.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 80).put("total_tokens", 180);
            var response = new MockClientHttpResponse(json.writeValueAsBytes(body), HttpStatus.OK);
            response.getHeaders().set("Content-Type", "application/json");
            return response;
        });
        var tool = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("search_report_passages").description("受限报告查询")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"]}").build();
            }
            @Override public String call(String arguments) { throw new AssertionError("Adapter must not execute tools"); }
        };
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DeepSeekChatAutoConfiguration.class,
                        ChatClientAutoConfiguration.class, ToolCallingAutoConfiguration.class, SpringAiRetryAutoConfiguration.class))
                .withUserConfiguration(ResearchAiConfiguration.class, ResearchQuestionModel.class)
                .withBean(RestClient.Builder.class, () -> builder)
                .withPropertyValues("spring.ai.model.chat=deepseek", "spring.ai.deepseek.api-key=offline-test-key",
                        "spring.ai.deepseek.base-url=https://offline.invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    try (var execution = new ResearchModelExecution()) {
                        execution.dispatch();
                        var answer = context.getBean(ResearchQuestionModel.class).call(List.of(new SystemMessage("Return JSON")), List.of(tool));
                        assertThat(answer.hasToolCalls()).isEqualTo(toolCall);
                        assertThat(execution.usageKnown()).isTrue();
                        assertThat(execution.inputTokens).isEqualTo(100);
                    }
                });
        server.verify();
    }
}
