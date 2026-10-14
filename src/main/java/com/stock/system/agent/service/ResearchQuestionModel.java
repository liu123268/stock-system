package com.stock.system.agent.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

/** 使用框架模型传输层，每次只发一次请求；有限工具循环明确由业务层管理。 */
@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
public class ResearchQuestionModel {
    private final ChatModel model;
    public ResearchQuestionModel(ChatModel model) { this.model = model; }
    public AssistantMessage call(List<Message> messages, List<ToolCallback> tools) {
        var options = DeepSeekChatOptions.builder().model("deepseek-flash").disableThinking().maxTokens(2000)
                .toolCallbacks(tools).toolChoice(tools.isEmpty() ? "none" : "auto");
        if (tools.isEmpty()) options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        try {
            var response = model.call(new Prompt(messages, options.build()));
            if (response != null) {
                var usage = response.getMetadata().getUsage();
                boolean reported = usage != null && usage.getPromptTokens() != null && usage.getCompletionTokens() != null
                        && (usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0);
                var execution = ResearchModelExecution.current();
                if (execution != null) {
                    execution.usage(response.getMetadata().getModel(), reported ? usage.getPromptTokens() : null,
                            reported ? usage.getCompletionTokens() : null);
                    execution.remainingMillis();
                }
            }
            if (response == null || response.getResults().size() != 1 || response.getResult().getOutput() == null)
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
            String reason = response.getResult().getMetadata().getFinishReason();
            if (!"stop".equalsIgnoreCase(reason) && !"tool_calls".equalsIgnoreCase(reason))
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
            return response.getResult().getOutput();
        } catch (BusinessException failure) { throw failure; }
        catch (RuntimeException failure) { throw new BusinessException(ErrorCode.RESEARCH_AGENT_UNAVAILABLE); }
    }
}
