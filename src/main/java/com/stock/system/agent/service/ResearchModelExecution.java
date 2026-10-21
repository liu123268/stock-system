package com.stock.system.agent.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;

/** 每次固定梳理的执行上下文，只存运行控制与用量，不保存提示词。 */
public final class ResearchModelExecution implements AutoCloseable {
    private static final ThreadLocal<ResearchModelExecution> CURRENT = new ThreadLocal<>();
    private final long deadline = System.nanoTime() + 60_000_000_000L;
    public boolean dispatched;
    public String model;
    public Integer inputTokens;
    public Integer outputTokens;
    public int modelCalls;
    public int toolCalls;
    private final int maxModelCalls;
    private boolean waitingForUsage;
    private boolean unknownUsage;
    private ResearchExperimentBudget experimentBudget;
    private String experimentRequestId;
    private Long runId;
    private String requestId;
    private Integer upstreamStatus;
    private ResearchAgentProgress progress = ResearchAgentProgress.NONE;
    private Runnable continuationCheck = () -> {};
    public ResearchModelFailure.Diagnostic failure;
    public final java.util.List<ToolTrace> toolTrace = new java.util.ArrayList<>();

    public ResearchModelExecution() {
        this(1);
    }

    public ResearchModelExecution(int maxModelCalls) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested research generation");
        this.maxModelCalls = maxModelCalls;
        CURRENT.set(this);
    }

    public static ResearchModelExecution current() { return CURRENT.get(); }
    public void bind(long runId, String requestId) { this.runId = runId; this.requestId = requestId; }
    public void observe(ResearchAgentProgress progress, Runnable continuationCheck) {
        this.progress = progress;
        this.continuationCheck = continuationCheck;
    }
    public void stage(ResearchAgentProgress.Stage stage) {
        remainingMillis();
        progress.stage(stage);
    }
    public Long runId() { return runId; }
    public String requestId() { return requestId; }
    public Integer upstreamStatus() { return upstreamStatus; }
    public void upstreamStatus(int status) { upstreamStatus = status; }

    public int remainingMillis() {
        continuationCheck.run();
        long millis = (deadline - System.nanoTime()) / 1_000_000L;
        if (millis <= 0) throw new BusinessException(ErrorCode.RESEARCH_AGENT_TIMEOUT);
        return (int) millis;
    }

    public void usage(String model, Integer input, Integer output) {
        this.model = model;
        waitingForUsage = false;
        if (input == null || output == null || input < 0 || output < 0) {
            unknownUsage = true;
            return;
        }
        this.inputTokens = Math.addExact(this.inputTokens == null ? 0 : this.inputTokens, input);
        this.outputTokens = Math.addExact(this.outputTokens == null ? 0 : this.outputTokens, output);
        if (experimentBudget != null) experimentBudget.settle(experimentRequestId, input, output);
    }

    public void dispatch() {
        dispatch(null, 0);
    }

    public void dispatch(ResearchExperimentBudget experimentBudget, int inputBytes) {
        remainingMillis();
        progress.stage(ResearchAgentProgress.Stage.GENERATING);
        if (modelCalls >= maxModelCalls) throw new BusinessException(ErrorCode.RESEARCH_AGENT_EXECUTION_LIMIT);
        if (waitingForUsage) unknownUsage = true;
        this.experimentBudget = experimentBudget;
        this.experimentRequestId = java.util.UUID.randomUUID().toString();
        if (experimentBudget != null) experimentBudget.reserve(experimentRequestId, inputBytes);
        modelCalls++;
        upstreamStatus = null;
        dispatched = true;
        waitingForUsage = true;
    }

    public boolean usageKnown() { return !unknownUsage && !waitingForUsage; }
    public record ToolTrace(String name, String status, long elapsedMillis,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                            String errorCode) {
        public ToolTrace(String name, String status, long elapsedMillis) { this(name, status, elapsedMillis, null); }
    }

    @Override public void close() { CURRENT.remove(); }
}
