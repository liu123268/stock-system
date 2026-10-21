package com.stock.system.agent.service;

import java.util.List;

/** 只公开确定的工作阶段，不传递模型思考、参数或尚未校验的正文。 */
public interface ResearchAgentProgress {
    ResearchAgentProgress NONE = new ResearchAgentProgress() {};
    enum Stage { READING, QUERYING, GENERATING, VALIDATING, SAVING }
    default void bound(long runId) {}
    default void stage(Stage stage) {}
    default boolean streamsBody() { return false; }
    /** 已校验的临时段落快照；空列表撤掉临时内容，不是成功消息。 */
    default void draft(List<String> paragraphs) {}
    default boolean cancellationRequested() { return false; }
}
