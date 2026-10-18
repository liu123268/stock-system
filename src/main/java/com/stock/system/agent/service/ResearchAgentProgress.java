package com.stock.system.agent.service;

/** 只公开确定的工作阶段，不传递模型思考、参数或尚未校验的正文。 */
public interface ResearchAgentProgress {
    ResearchAgentProgress NONE = new ResearchAgentProgress() {};
    enum Stage { READING, QUERYING, GENERATING, VALIDATING, SAVING }
    default void bound(long runId) {}
    default void stage(Stage stage) {}
    default boolean cancellationRequested() { return false; }
}
