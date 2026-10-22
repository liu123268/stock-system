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
    public enum Rule { SHAPE, ITEM_TEXT, INTERNAL_IDENTIFIER, SOURCE_IDS, UNKNOWN_SOURCE, DUPLICATE_SOURCE,
        SOURCE_REQUIRED, CHANGE_REFERENCES, MEMBER_ID, MEMBER_LATEST, MEMBER_SOURCE, MEMBER_COVERAGE }
    public static final class Rejected extends BusinessException {
        private final Rule rule;
        private Rejected(Rule rule) { super(ErrorCode.RESEARCH_AGENT_INVALID_RESULT); this.rule = rule; }
        public Rule rule() { return rule; }
    }
    public ResearchSummary validate(ResearchSummary summary, List<SourceBinding> sources) {
        return validate(summary, sources, true);
    }

    /** 预览仅允许尚未到达的成员暂缺；已有条目仍检查全部引用和前后关系。 */
    public ResearchSummary validateDraft(ResearchSummary summary, List<SourceBinding> sources) {
        return validate(summary, sources, false);
    }

    private ResearchSummary validate(ResearchSummary summary, List<SourceBinding> sources, boolean complete) {
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
        require(!summary.overview().sourceIds().isEmpty() || sources.isEmpty(), Rule.SOURCE_REQUIRED);
        for (ResearchSummary.Item change : summary.changes()) {
            require(change != null);
            item(change.text(), change.sourceIds(), allowed, internalText);
            Set<String> authors = new HashSet<>();
            for (String id : change.sourceIds()) {
                authors.add(allowed.get(id).authorId());
            }
            require(change.sourceIds().size() >= 2 && authors.size() == 1, Rule.CHANGE_REFERENCES);
        }
        Set<String> members = new HashSet<>();
        for (ResearchSummary.Member member : summary.members()) {
            require(member != null);
            item(member.text(), member.sourceIds(), allowed, internalText);
            require(member.authorId() != null && latest.containsKey(member.authorId())
                    && members.add(member.authorId()), Rule.MEMBER_ID);
            require(member.sourceIds().contains(latest.get(member.authorId())), Rule.MEMBER_LATEST);
            for (String id : member.sourceIds()) {
                require(allowed.get(id).authorId().equals(member.authorId()), Rule.MEMBER_SOURCE);
            }
        }
        require(!complete || members.equals(latest.keySet()), Rule.MEMBER_COVERAGE);
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
        // alternatives 会含多项以 | 连接的编号，分组让前后边界约束整组，不能直接删除。
        //noinspection RegExpUnnecessaryNonCapturingGroup
        return Pattern.compile("(?<![A-Za-z0-9_-])(?:" + alternatives + ")(?![A-Za-z0-9_-])");
    }

    private void item(String text, List<String> ids, Map<String, SourceBinding> allowed, Pattern internalText) {
        require(text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= 1000, Rule.ITEM_TEXT);
        // 昵称或原文恰含M1等真实字面时保留，不把局部展示约束变成全局内容禁令。
        require(internalText == null || !internalText.matcher(text).find(), Rule.INTERNAL_IDENTIFIER);
        require(ids != null && ids.size() <= 8, Rule.SOURCE_IDS);
        Set<String> distinct = new HashSet<>();
        for (String id : ids) {
            require(id != null && allowed.containsKey(id), Rule.UNKNOWN_SOURCE);
            require(distinct.add(id), Rule.DUPLICATE_SOURCE);
        }
    }

    private void require(boolean valid) {
        require(valid, Rule.SHAPE);
    }
    private void require(boolean valid, Rule rule) {
        if (!valid) throw new Rejected(rule);
    }
}
