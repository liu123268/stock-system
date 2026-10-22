package com.stock.system.agent.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 梳理与追问共用一次原生传输；业务校验在调用线程，已派发请求在原期限内收尾结算。 */
final class ResearchModelStream {
    private final ChatModel model;

    ResearchModelStream(ChatModel model) { this.model = model; }

    Result call(Prompt prompt, Consumer<String> content, String failureStage) {
        // 执行上下文由外层运行入口拥有和关闭，此处只借用。
        var execution = Objects.requireNonNull(ResearchModelExecution.current());
        int callsBefore = execution.modelCalls;
        var text = new StringBuilder();
        var toolCalls = new ArrayList<AssistantMessage.ToolCall>();
        String modelName = null, finish = null;
        Integer input = null, output = null;
        RuntimeException previewFailure = null;
        boolean invalid = false;
        var receiver = new Receiver();
        try {
            var deadline = Mono.delay(Duration.ofMillis(execution.transportRemainingMillis()))
                    .flatMap(ignored -> Mono.error(new BusinessException(ErrorCode.RESEARCH_AGENT_TIMEOUT)));
            var chunks = model.stream(prompt).contextWrite(context -> context.put(ResearchModelExecution.class, execution))
                    .takeUntilOther(deadline).map(receiver::receive)
                    // 框架内部 flatMap 不承诺背压时的顺序；先快速接收有界数据，再由业务线程逐项校验。
                    .onBackpressureBuffer(4096);
            for (var chunk : chunks.toIterable(1)) {
                String reportedModel = chunk.model();
                if (!reportedModel.isBlank()) modelName = reportedModel;
                if (chunk.input() >= 0 && chunk.output() >= 0 && (chunk.input() > 0 || chunk.output() > 0)) {
                    input = chunk.input(); output = chunk.output();
                }
                if (!chunk.finish().isBlank()) finish = chunk.finish();
                toolCalls.addAll(chunk.tools());
                String delta = chunk.text();
                if (delta.isEmpty()) continue;
                if (text.length() + delta.length() > 32_000) { invalid = true; continue; }
                text.append(delta);
                if (!invalid && !receiver.invalid && previewFailure == null && toolCalls.isEmpty()) {
                    try { content.accept(delta); }
                    catch (RuntimeException failure) { previewFailure = failure; }
                }
            }
            if (previewFailure != null) throw previewFailure;
            if (invalid || receiver.invalid || execution.modelCalls != callsBefore + 1
                    || !("stop".equalsIgnoreCase(finish) || "tool_calls".equalsIgnoreCase(finish)))
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
            AssistantMessage.Builder<?> reply = AssistantMessage.builder();
            reply.content(text.toString());
            reply.toolCalls(List.copyOf(toolCalls));
            return new Result(reply.build(), finish, modelName, input, output);
        } catch (RuntimeException failure) { throw ResearchModelFailure.translate(failure, failureStage); }
        finally {
            if (execution.modelCalls > callsBefore) execution.usage(modelName, input, output);
        }
    }

    record Result(AssistantMessage message, String finishReason, String model, Integer inputTokens, Integer outputTokens) {}

    /** 队列只保留业务所需字段，丢弃框架原始元数据／思考；正文与工具总载荷最多 65536 字符。 */
    private static final class Receiver {
        private int characters, calls;
        private volatile boolean invalid;

        private Chunk receive(ChatResponse response) {
            var usage = response.getMetadata().getUsage();
            String model = response.getMetadata().getModel();
            if (model.length() > 128) { invalid = true; model = ""; }
            String text = "", finish = "";
            List<AssistantMessage.ToolCall> tools = List.of();
            if (response.getResults().size() > 1) invalid = true;
            else if (!response.getResults().isEmpty()) {
                var generation = response.getResults().get(0);
                var reason = generation.getMetadata().getFinishReason();
                if (reason != null) finish = reason;
                if (finish.length() > 32) { invalid = true; finish = ""; }
                var message = generation.getOutput();
                String delta = message.getText();
                if (delta != null) text = delta;
                tools = message.getToolCalls();
                calls += tools.size();
                long received = (long) characters + text.length();
                for (var tool : tools) received += length(tool.id()) + length(tool.type()) + length(tool.name()) + length(tool.arguments());
                if (received > 65_536 || calls > 8) invalid = true;
                else characters = (int) received;
            }
            if (invalid) { text = ""; tools = List.of(); }
            return new Chunk(text, List.copyOf(tools), model, finish, usage.getPromptTokens(), usage.getCompletionTokens());
        }
        private static int length(String value) { return value == null ? 0 : value.length(); }
    }

    private record Chunk(String text, List<AssistantMessage.ToolCall> tools, String model, String finish, int input, int output) {}
}
