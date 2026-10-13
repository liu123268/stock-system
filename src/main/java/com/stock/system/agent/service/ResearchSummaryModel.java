package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
public class ResearchSummaryModel {
    private final ChatClient chatClient;
    private final JsonMapper parser = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ResearchSummaryModel(ChatClient.Builder builder) {
        this.chatClient = builder.clone().build();
    }

    /** 一次固定资料梳理，不注册工具；公共生成入口的预算、幂等和会话尚待接入。 */
    public Result generate(ResearchSummaryPrompt.Input input) {
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .system(input.system())
                    .user(input.user())
                    .options(DeepSeekChatOptions.builder().disableThinking().maxTokens(2000)
                            .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()))
                    .call().chatResponse();
        } catch (RuntimeException exception) {
            // 不把请求正文、密钥或上游错误作为业务错误返回。
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_UNAVAILABLE);
        }
        if (response == null || response.getResults().size() != 1 || response.getResult() == null
                || !"stop".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().hasToolCalls()) {
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
        }
        ResearchSummary summary;
        try {
            String text = response.getResult().getOutput().getText();
            if (text == null || text.length() > 32_000) {
                throw new IllegalArgumentException("Invalid model content size");
            }
            summary = parser.readValue(text, ResearchSummary.class);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
        }
        var usage = response.getMetadata().getUsage();
        return new Result(summary, response.getMetadata().getModel(),
                usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens());
    }

    public record Result(ResearchSummary summary, String model, Integer inputTokens, Integer outputTokens) {}
}
