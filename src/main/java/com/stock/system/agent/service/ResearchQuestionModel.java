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
import java.util.function.Consumer;

/** 使用框架模型传输层，每次只发一次请求；有限工具循环明确由业务层管理。 */
@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
public class ResearchQuestionModel {
    private final ChatModel model;
    private final ResearchModelStream stream;
    // 与本类相同的 deepseek 开关启用框架自动配置；装配由实际 ApplicationContext 测试核对。
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public ResearchQuestionModel(ChatModel model) { this.model = model; this.stream = new ResearchModelStream(model); }
    private DeepSeekChatOptions options(List<ToolCallback> tools) {
        return DeepSeekChatOptions.builder().model("deepseek-flash").disableThinking().maxTokens(2000)
                .toolCallbacks(tools).toolChoice(tools.isEmpty() ? "none" : "auto")
                // A round offering tools may still return a final answer; it needs the same JSON contract.
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()).build();
    }
    public AssistantMessage call(List<Message> messages, List<ToolCallback> tools) {
        try {
            var response = model.call(new Prompt(messages, options(tools)));
            // 框架契约保证响应与 Usage 非空；没有用量时返回零值，不能当作已知零费用。
            var usage = response.getMetadata().getUsage();
            boolean reported = usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0;
            // 上层运行入口负责关闭；这里只借用执行上下文。
            //noinspection resource
            var execution = ResearchModelExecution.current();
            if (execution != null) {
                execution.usage(response.getMetadata().getModel(), reported ? usage.getPromptTokens() : null,
                        reported ? usage.getCompletionTokens() : null);
                execution.remainingMillis();
            }
            var results = response.getResults();
            if (results.size() != 1)
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
            var result = results.get(0);
            String reason = result.getMetadata().getFinishReason();
            if (!"stop".equalsIgnoreCase(reason) && !"tool_calls".equalsIgnoreCase(reason))
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
            return result.getOutput();
        } catch (RuntimeException failure) { throw ResearchModelFailure.translate(failure, "QUESTION_MODEL"); }
    }

    /** 与首次梳理共用原生传输、期限和用量收尾，业务工具循环仍在上层。 */
    public AssistantMessage stream(List<Message> messages, List<ToolCallback> tools, Consumer<String> content) {
        return stream.call(new Prompt(messages, options(tools)), content, "QUESTION_MODEL").message();
    }
}
