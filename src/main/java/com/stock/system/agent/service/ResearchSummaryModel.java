package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.function.Consumer;

@Component
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "deepseek")
public class ResearchSummaryModel {
    private final ChatClient chatClient;
    private final ResearchModelStream stream;
    private final JsonMapper parser = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public ResearchSummaryModel(ChatClient.Builder builder, ChatModel model) {
        this.chatClient = builder.clone().build();
        this.stream = new ResearchModelStream(model);
    }

    /** 一次固定资料同步梳理，不注册工具；公共运行入口负责预算、幂等与个人记录。 */
    public Result generate(ResearchSummaryPrompt.Input input) {
        ChatResponse response;
        try {
            response = chatClient.prompt()
                    .system(input.system())
                    .user(input.user())
                    .options(DeepSeekChatOptions.builder().model("deepseek-flash").disableThinking().maxTokens(2000)
                            .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()))
                    .call().chatResponse();
        } catch (RuntimeException exception) {
            throw ResearchModelFailure.translate(exception, "SUMMARY_MODEL");
        }
        var execution = ResearchModelExecution.current();
        if (response != null && execution != null) {
            var usage = response.getMetadata().getUsage();
            boolean reported = usage != null && usage.getPromptTokens() != null && usage.getCompletionTokens() != null
                    && (usage.getPromptTokens() > 0 || usage.getCompletionTokens() > 0);
            execution.usage(response.getMetadata().getModel(), reported ? usage.getPromptTokens() : null,
                    reported ? usage.getCompletionTokens() : null);
            execution.remainingMillis();
        }
        if (response == null || response.getResults().size() != 1 || response.getResult() == null
                || !"stop".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().hasToolCalls()) {
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
        }
        ResearchSummary summary = parse(response.getResult().getOutput().getText());
        var usage = response.getMetadata().getUsage();
        return new Result(summary, response.getMetadata().getModel(),
                usage == null ? null : usage.getPromptTokens(), usage == null ? null : usage.getCompletionTokens());
    }

    public record Result(ResearchSummary summary, String model, Integer inputTokens, Integer outputTokens) {}

    /** 与追问共用实际原生流；临时分块交给业务层，完整结果仍严格解析后返回。 */
    public Result stream(ResearchSummaryPrompt.Input input, Consumer<String> content) {
        var options = DeepSeekChatOptions.builder().model("deepseek-flash").disableThinking().maxTokens(2000)
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build()).build();
        var generated = stream.call(new Prompt(List.of(new SystemMessage(input.system()), new UserMessage(input.user())), options),
                content, "SUMMARY_MODEL");
        if (!"stop".equalsIgnoreCase(generated.finishReason()) || generated.message().hasToolCalls())
            throw ResearchModelFailure.translate(new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT), "SUMMARY_MODEL");
        ResearchSummary summary = parse(generated.message().getText());
        return new Result(summary, generated.model(), generated.inputTokens(), generated.outputTokens());
    }

    private ResearchSummary parse(String text) {
        String stage = "SUMMARY_REPLY_JSON";
        try {
            var payload = parser.readTree(ResearchModelJson.body(text));
            stage = "SUMMARY_REPLY_FIELDS";
            if (payload == null || !payload.isObject() || payload.size() != 4 || !payload.has("overview")
                    || !payload.has("changes") || !payload.has("members") || !payload.has("pendingItems"))
                throw new IllegalArgumentException();
            stage = "SUMMARY_REPLY_OVERVIEW";
            var overview = parser.treeToValue(payload.path("overview"), ResearchSummary.Item.class);
            stage = "SUMMARY_REPLY_CHANGES";
            var changes = List.of(parser.treeToValue(payload.path("changes"), ResearchSummary.Item[].class));
            stage = "SUMMARY_REPLY_MEMBERS";
            var members = List.of(parser.treeToValue(payload.path("members"), ResearchSummary.Member[].class));
            stage = "SUMMARY_REPLY_PENDING";
            var pending = List.of(parser.treeToValue(payload.path("pendingItems"), ResearchSummary.Item[].class));
            return new ResearchSummary(overview, changes, members, pending);
        }
        catch (RuntimeException invalid) {
            throw ResearchModelFailure.translate(new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT), stage);
        }
    }
}
