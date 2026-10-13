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

    public ResearchModelExecution() {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested research generation");
        CURRENT.set(this);
    }

    public static ResearchModelExecution current() { return CURRENT.get(); }

    public int remainingMillis() {
        long millis = (deadline - System.nanoTime()) / 1_000_000L;
        if (millis <= 0) throw new BusinessException(ErrorCode.RESEARCH_AGENT_TIMEOUT);
        return (int) millis;
    }

    public void usage(String model, Integer input, Integer output) {
        this.model = model;
        this.inputTokens = input;
        this.outputTokens = output;
    }

    @Override public void close() { CURRENT.remove(); }
}
