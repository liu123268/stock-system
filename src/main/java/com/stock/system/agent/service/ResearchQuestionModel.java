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
import java.util.ArrayList;
import java.util.function.Consumer;
import java.time.Duration;
import reactor.core.publisher.Mono;

/** 使用框架模型传输层，每次只发一次请求；有限工具循环明确由业务层管理。 */
@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
public class ResearchQuestionModel {
    private final ChatModel model;
    // 与本类相同的 deepseek 开关启用框架自动配置；装配由实际 ApplicationContext 测试核对。
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public ResearchQuestionModel(ChatModel model) { this.model = model; }
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

    /** 一次真实上游流请求。调用线程消费有限分块，预览失败后仍收尾取用量，不自动重试。 */
    public AssistantMessage stream(List<Message> messages, List<ToolCallback> tools, Consumer<String> content) {
        // 上层运行入口负责关闭；这里只借用执行上下文。
        var execution = java.util.Objects.requireNonNull(ResearchModelExecution.current());
        int callsBefore = execution.modelCalls;
        StringBuilder text = new StringBuilder();
        var toolCalls = new ArrayList<AssistantMessage.ToolCall>();
        String modelName = null;
        Integer input = null, output = null;
        String finish = null;
        RuntimeException previewFailure = null;
        boolean invalid = false;
        try {
            var deadline = Mono.delay(Duration.ofMillis(execution.transportRemainingMillis()))
                    .flatMap(ignored -> Mono.error(new BusinessException(ErrorCode.RESEARCH_AGENT_TIMEOUT)));
            var chunks = model.stream(new Prompt(messages, options(tools)))
                    .contextWrite(context -> context.put(ResearchModelExecution.class, execution)).takeUntilOther(deadline);
            for (var chunk : chunks.toIterable(1)) {
                var usage = chunk.getMetadata().getUsage();
                modelName = chunk.getMetadata().getModel();
                if (usage.getPromptTokens() >= 0 && usage.getCompletionTokens() >= 0
                        && (usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0)) {
                    input = usage.getPromptTokens(); output = usage.getCompletionTokens();
                }
                var results = chunk.getResults();
                if (results.size() > 1) { invalid = true; continue; }
                if (results.isEmpty()) continue; // 兼容只含用量的末帧。
                var result = results.get(0);
                String reason = result.getMetadata().getFinishReason();
                if (reason != null && !reason.isBlank()) finish = reason;
                var message = result.getOutput();
                toolCalls.addAll(message.getToolCalls());
                if (toolCalls.size() > 8) invalid = true;
                String delta = message.getText();
                if (delta == null || delta.isEmpty()) continue;
                if (text.length() + delta.length() > 32_000) { invalid = true; continue; }
                text.append(delta);
                if (!invalid && previewFailure == null && toolCalls.isEmpty()) {
                    try { content.accept(delta); }
                    catch (RuntimeException failure) { previewFailure = failure; }
                }
            }
            if (previewFailure != null) throw previewFailure;
            if (invalid || execution.modelCalls != callsBefore + 1 ||
                    !("stop".equalsIgnoreCase(finish) || "tool_calls".equalsIgnoreCase(finish)))
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
            AssistantMessage.Builder<?> reply = AssistantMessage.builder();
            reply.content(text.toString());
            reply.toolCalls(List.copyOf(toolCalls));
            return reply.build();
        } catch (RuntimeException failure) { throw ResearchModelFailure.translate(failure, "QUESTION_MODEL"); }
        finally {
            if (execution.modelCalls > callsBefore) execution.usage(modelName, input, output);
        }
    }
}
