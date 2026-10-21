package com.stock.system.agent;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.agent.service.ResearchSummaryPrompt;
import com.stock.system.agent.service.ResearchSummaryValidator;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchSummaryValidatorTest {
    private final ResearchSummaryValidator validator = new ResearchSummaryValidator();
    private final ResearchSummaryPrompt prompt = new ResearchSummaryPrompt(JsonMapper.builder().findAndAddModules().build());

    @Test
    void rejectsMissingWrongDuplicateOrStaleMemberReferences() {
        var valid = ResearchSummaryFixtures.summary();
        rejects(new ResearchSummary(valid.overview(), valid.changes(), List.of(), valid.pendingItems()));
        rejects(new ResearchSummary(valid.overview(), valid.changes(), List.of(
                new ResearchSummary.Member("M1", "旧观点", List.of("op-1")), valid.members().get(1)), valid.pendingItems()));
        rejects(new ResearchSummary(valid.overview(), valid.changes(), List.of(
                new ResearchSummary.Member("M1", "别人的观点", List.of("op-2", "op-3")), valid.members().get(1)), valid.pendingItems()));
        rejects(new ResearchSummary(valid.overview(), valid.changes(), List.of(
                valid.members().get(0), valid.members().get(0)), valid.pendingItems()));
    }

    @Test
    void aChangeNeedsTwoDistinctRecordsFromTheSameMember() {
        var valid = ResearchSummaryFixtures.summary();
        for (List<String> ids : List.of(List.of("op-1"), List.of("op-1", "op-3"), List.of("op-1", "op-1"))) {
            rejects(new ResearchSummary(valid.overview(), List.of(new ResearchSummary.Item("变化", ids)),
                    valid.members(), valid.pendingItems()));
        }
    }

    @Test
    void rejectsMissingBlankOversizedAndUnknownOutput() {
        var valid = ResearchSummaryFixtures.summary();
        rejects(null);
        rejects(new ResearchSummary(null, valid.changes(), valid.members(), valid.pendingItems()));
        rejects(new ResearchSummary(new ResearchSummary.Item(" ", List.of("op-1")),
                valid.changes(), valid.members(), valid.pendingItems()));
        rejects(new ResearchSummary(new ResearchSummary.Item("文".repeat(1001), List.of("op-1")),
                valid.changes(), valid.members(), valid.pendingItems()));
        rejects(new ResearchSummary(valid.overview(), null, valid.members(), valid.pendingItems()));
        rejects(new ResearchSummary(new ResearchSummary.Item("无引用结论", List.of()),
                valid.changes(), valid.members(), valid.pendingItems()));
    }

    @Test
    void acceptsRealReferencesAndDoesNotPretendToCheckSemanticTruth() {
        var result = validator.validate(ResearchSummaryFixtures.summary(), prompt.build(ResearchSummaryFixtures.sources()).sources());
        assertThat(result).isEqualTo(ResearchSummaryFixtures.summary());
        assertThatThrownBy(() -> result.overview().sourceIds().add("op-999"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsKnownInternalIdentifiersInBodyWithoutChangingStructuredReferences() {
        var valid = ResearchSummaryFixtures.summary();
        for (String text : List.of("成员M1从看多转为中性", "成员甲先前看多（op-1），随后转中性（op-2）")) {
            rejects(new ResearchSummary(new ResearchSummary.Item(text, List.of("op-1", "op-2")), valid.changes(), valid.members(), valid.pendingItems()));
        }
        assertThat(validator.validate(valid, prompt.build(ResearchSummaryFixtures.sources()).sources()).changes().get(0).sourceIds())
                .containsExactly("op-1", "op-2");
    }

    @Test void anActualNicknameMatchingAnAliasAndAnOriginalFinancialTermRemainLegalText() {
        var source = ResearchSummaryFixtures.sources(List.of(ResearchSummaryFixtures.opinion(1L, 11L, "M1", "NEUTRAL",
                "观察货币M1增速，不是成员编号。", ResearchSummaryFixtures.DATE)));
        var summary = new ResearchSummary(new ResearchSummary.Item("M1观察货币M1增速。", List.of("op-1")), List.of(),
                List.of(new ResearchSummary.Member("M1", "M1关注货币M1增速。", List.of("op-1"))), List.of());
        assertThat(validator.validate(summary, prompt.build(source).sources())).isEqualTo(summary);
        var originalTerm = ResearchSummaryFixtures.sources(List.of(ResearchSummaryFixtures.opinion(1L, 11L, "甲", "NEUTRAL",
                "关注M1增速。", ResearchSummaryFixtures.DATE)));
        var term = new ResearchSummary(new ResearchSummary.Item("甲关注M1增速。", List.of("op-1")), List.of(),
                List.of(new ResearchSummary.Member("M1", "甲关注M1增速。", List.of("op-1"))), List.of());
        assertThat(validator.validate(term, prompt.build(originalTerm).sources())).isEqualTo(term);
    }

    private void rejects(ResearchSummary summary) {
        assertThatThrownBy(() -> validator.validate(summary, prompt.build(ResearchSummaryFixtures.sources()).sources()))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_INVALID_RESULT));
    }
}
