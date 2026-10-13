package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.agent.service.ResearchSummaryPrompt.SourceBinding;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 检查结构与引用关系；不能据此宣称文字的每个判断均已获得语义证明。 */
@Component
public class ResearchSummaryValidator {
    public ResearchSummary validate(ResearchSummary summary, List<SourceBinding> sources) {
        require(summary != null && summary.overview() != null);
        require(summary.changes() != null && summary.changes().size() <= 8);
        require(summary.pendingItems() != null && summary.pendingItems().size() <= 8);
        require(summary.members() != null && summary.members().size() <= sources.size());
        Map<String, SourceBinding> allowed = new HashMap<>();
        Map<String, String> latest = new HashMap<>();
        for (SourceBinding source : sources) {
            allowed.put(source.sourceId(), source);
            // 输入来源已按日期、原记录 ID 升序排列，最后一条即该成员最新观点。
            latest.put(source.authorId(), source.sourceId());
        }
        item(summary.overview().text(), summary.overview().sourceIds(), allowed);
        require(!summary.overview().sourceIds().isEmpty() || sources.isEmpty());
        for (ResearchSummary.Item change : summary.changes()) {
            require(change != null);
            item(change.text(), change.sourceIds(), allowed);
            Set<String> authors = new HashSet<>();
            for (String id : change.sourceIds()) {
                authors.add(allowed.get(id).authorId());
            }
            require(change.sourceIds().size() >= 2 && authors.size() == 1);
        }
        Set<String> members = new HashSet<>();
        for (ResearchSummary.Member member : summary.members()) {
            require(member != null);
            item(member.text(), member.sourceIds(), allowed);
            require(member.authorId() != null && latest.containsKey(member.authorId())
                    && members.add(member.authorId()));
            require(member.sourceIds().contains(latest.get(member.authorId())));
            for (String id : member.sourceIds()) {
                require(allowed.get(id).authorId().equals(member.authorId()));
            }
        }
        require(members.equals(latest.keySet()));
        for (ResearchSummary.Item pending : summary.pendingItems()) {
            require(pending != null);
            item(pending.text(), pending.sourceIds(), allowed);
        }
        // 防止调用方在校验通过后修改列表，把未校验内容混进结果。
        return new ResearchSummary(copy(summary.overview()),
                summary.changes().stream().map(this::copy).toList(),
                summary.members().stream().map(member -> new ResearchSummary.Member(
                        member.authorId(), member.text(), List.copyOf(member.sourceIds()))).toList(),
                summary.pendingItems().stream().map(this::copy).toList());
    }

    private ResearchSummary.Item copy(ResearchSummary.Item item) {
        return new ResearchSummary.Item(item.text(), List.copyOf(item.sourceIds()));
    }

    private void item(String text, List<String> ids, Map<String, SourceBinding> allowed) {
        require(text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= 1000);
        require(ids != null && ids.size() <= 8);
        Set<String> distinct = new HashSet<>();
        for (String id : ids) {
            require(id != null && allowed.containsKey(id) && distinct.add(id));
        }
    }

    private void require(boolean valid) {
        if (!valid) {
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
        }
    }
}
