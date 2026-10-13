package com.stock.system.agent;

import com.stock.system.agent.service.ResearchSummaryModel;
import com.stock.system.agent.service.ResearchSummaryPrompt;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

class ResearchSummaryModelTest {
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final ResearchSummaryPrompt prompt = new ResearchSummaryPrompt(mapper);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeepSeekChatAutoConfiguration.class,
                    ChatClientAutoConfiguration.class, ToolCallingAutoConfiguration.class,
                    SpringAiRetryAutoConfiguration.class))
            .withUserConfiguration(ResearchAiConfiguration.class, ResearchSummaryModel.class);

    @Test
    void defaultConfigurationHasNoSummaryModelAndNeedsNoKey() {
        runner.withPropertyValues("spring.ai.model.chat=none", "spring.ai.deepseek.api-key=")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ResearchSummaryModel.class));
    }

    @Test
    void realAdapterSendsRulesAndActualMaterialsThenParsesAnswerAndUsage() throws Exception {
        try (Provider provider = new Provider("valid")) {
            enabled(provider).run(context -> {
                assertThat(context).hasNotFailed();
                var result = context.getBean(ResearchSummaryModel.class).generate(prompt.build(ResearchSummaryFixtures.sources()));
                assertThat(result.summary()).isEqualTo(ResearchSummaryFixtures.summary());
                assertThat(result.model()).isEqualTo("offline-model");
                assertThat(result.inputTokens()).isEqualTo(100);
                assertThat(provider.requests).hasSize(1);
                JsonNode request = provider.requests.get(0);
                assertThat(request.path("response_format").path("type").asString()).isEqualTo("json_object");
                assertThat(request.path("thinking").path("type").asString()).isEqualTo("disabled");
                assertThat(request.path("max_tokens").asInt()).isEqualTo(2000);
                assertThat(request.has("tools")).isFalse();
                assertThat(request.path("messages").get(0).path("role").asString()).isEqualTo("system");
                assertThat(request.path("messages").get(0).path("content").asString())
                        .contains(ResearchSummaryPrompt.ANSWER_RULE);
                assertThat(request.path("messages").get(1).path("content").asString())
                        .contains("认为新订单可能增长", "sourceId", "authorName")
                        .doesNotContain("authorUserId", "workspaceId", "LoginUser", "offline-test-key");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-json", "extra-field", "truncated", "empty"})
    void unusableProviderOutputIsRejected(String mode) throws Exception {
        try (Provider provider = new Provider(mode)) {
            enabled(provider).run(context -> {
                assertThat(context).hasNotFailed();
                assertThatThrownBy(() -> context.getBean(ResearchSummaryModel.class)
                        .generate(prompt.build(ResearchSummaryFixtures.sources())))
                        .isInstanceOfSatisfying(BusinessException.class,
                                failure -> assertThat(failure.getErrorCode())
                                        .isEqualTo(ErrorCode.RESEARCH_AGENT_INVALID_RESULT));
                assertThat(provider.requests).hasSize(1);
            });
        }
    }

    @Test
    void providerFailureIsSanitizedAndDoesNotSendAnotherRequest() throws Exception {
        try (Provider provider = new Provider("failure")) {
            enabled(provider).run(context -> {
                assertThat(context).hasNotFailed();
                assertThatThrownBy(() -> context.getBean(ResearchSummaryModel.class)
                        .generate(prompt.build(ResearchSummaryFixtures.sources())))
                        .isInstanceOfSatisfying(BusinessException.class, failure -> {
                            assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_UNAVAILABLE);
                            assertThat(failure.getMessage()).doesNotContain("provider-secret-error");
                        });
                assertThat(provider.requests).hasSize(1);
            });
        }
    }

    private ApplicationContextRunner enabled(Provider provider) {
        return runner.withBean(RestClient.Builder.class, () -> provider.builder)
                .withPropertyValues("spring.ai.model.chat=deepseek", "spring.ai.deepseek.api-key=offline-test-key",
                "spring.ai.deepseek.base-url=https://offline.invalid",
                "spring.ai.deepseek.chat.model=deepseek-flash");
    }

    private class Provider implements AutoCloseable {
        private final RestClient.Builder builder = RestClient.builder();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        private final List<JsonNode> requests = new ArrayList<>();

        Provider(String mode) throws Exception {
            server.expect(requestTo("https://offline.invalid/chat/completions"))
                    .andExpect(method(HttpMethod.POST)).andRespond(request -> {
                    requests.add(mapper.readTree(((MockClientHttpRequest) request).getBodyAsString()));
                    String content = mapper.writeValueAsString(ResearchSummaryFixtures.summary());
                    if ("bad-json".equals(mode)) content = "this is not JSON";
                    if ("empty".equals(mode)) content = "";
                    if ("extra-field".equals(mode)) content = content.substring(0, content.length() - 1) + ",\"price\":999}";
                    var body = mapper.createObjectNode().put("id", "offline-summary")
                            .put("object", "chat.completion").put("created", 1).put("model", "offline-model");
                    var choice = body.putArray("choices").addObject().put("index", 0)
                            .put("finish_reason", "truncated".equals(mode) ? "length" : "stop");
                    choice.putObject("message").put("role", "assistant").put("content", content);
                    body.putObject("usage").put("prompt_tokens", 100).put("completion_tokens", 80).put("total_tokens", 180);
                    byte[] bytes = ("failure".equals(mode)
                            ? "{\"error\":{\"message\":\"provider-secret-error\"}}"
                            : mapper.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8);
                    var response = new MockClientHttpResponse(bytes,
                            "failure".equals(mode) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK);
                    response.getHeaders().set("Content-Type", "application/json");
                    return response;
            });
        }

        @Override
        public void close() throws Exception {
            server.verify();
        }
    }
}
