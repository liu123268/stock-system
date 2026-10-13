package com.stock.system.agent.dto;

import java.util.List;

/** 模型只生成文字和资料引用；范围、作者展示映射及生成时间由 Java 绑定。 */
public record ResearchSummary(
        Item overview,
        List<Item> changes,
        List<Member> members,
        List<Item> pendingItems
) {
    public record Item(String text, List<String> sourceIds) {}
    public record Member(String authorId, String text, List<String> sourceIds) {}
}
