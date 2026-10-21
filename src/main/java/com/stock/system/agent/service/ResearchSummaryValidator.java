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
import java.util.regex.Pattern;

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
        Pattern internalText = internalTextPattern(sources);
        item(summary.overview().text(), summary.overview().sourceIds(), allowed, internalText);
        require(!summary.overview().sourceIds().isEmpty() || sources.isEmpty());
        for (ResearchSummary.Item change : summary.changes()) {
            require(change != null);
            item(change.text(), change.sourceIds(), allowed, internalText);
            Set<String> authors = new HashSet<>();
            for (String id : change.sourceIds()) {
                authors.add(allowed.get(id).authorId());
            }
            require(change.sourceIds().size() >= 2 && authors.size() == 1);
        }
        Set<String> members = new HashSet<>();
        for (ResearchSummary.Member member : summary.members()) {
            require(member != null);
            item(member.text(), member.sourceIds(), allowed, internalText);
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
            item(pending.text(), pending.sourceIds(), allowed, internalText);
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

    private Pattern internalTextPattern(List<SourceBinding> sources) {
        var displayNames = sources.stream().map(source -> source.opinion().authorNickname()).collect(java.util.stream.Collectors.toSet());
        String originals = sources.stream().flatMap(source -> java.util.stream.Stream.of(source.opinion().reason(), source.opinion().observationNote()))
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining("\n"));
        var internals = new java.util.LinkedHashSet<String>();
        for (var source : sources) { internals.add(source.sourceId()); internals.add(source.authorId()); }
        internals.removeIf(value -> displayNames.contains(value) || tokenPattern(Pattern.quote(value)).matcher(originals).find());
        return internals.isEmpty() ? null : tokenPattern(internals.stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|")));
    }

    private Pattern tokenPattern(String alternatives) {
        return Pattern.compile("(?<![A-Za-z0-9_-])(?:" + alternatives + ")(?![A-Za-z0-9_-])");
    }

    private void item(String text, List<String> ids, Map<String, SourceBinding> allowed, Pattern internalText) {
        require(text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= 1000);
        // 昵称或原文恰含M1等真实字面时保留，不把局部展示约束变成全局内容禁令。
        require(internalText == null || !internalText.matcher(text).find());
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
