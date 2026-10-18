package com.stock.system.agent.service;

import com.stock.system.agent.dto.ResearchReply;
import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.agent.report.ReportCatalog;
import com.stock.system.agent.vo.ResearchAgentResponseVO.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ResearchReportAnswerSupportTest {
    private final Evidence operations = report("mt-operations");
    private final Evidence channels = report("mt-channels");
    ResearchReportAnswerSupportTest() throws java.io.IOException {}
    private Evidence report(String section) throws java.io.IOException {
        var passage = new ReportCatalog().forSymbol("600519.SH").stream().filter(row -> row.section().equals(section)).findFirst().orElseThrow();
        return new Evidence("report-" + passage.id(), "REPORT", "原文", null, null, null, passage);
    }
    private ResearchSummary.Item paragraph(String text, String... ids) { return new ResearchSummary.Item(text, List.of(ids)); }

    @Test void theActualV9GeneralizationCannotReplaceTheOriginalMaterialScope() {
        var reply = new ResearchReply(List.of(paragraph("原料采购主要根据生产和销售计划执行。", operations.sourceId())), List.of());
        var result = ResearchReportAnswerSupport.ground(reply, Map.of(operations.sourceId(), operations));
        assertThat(result.paragraphs().get(0).text()).endsWith(operations.reportPassage().text())
                .contains("其他原辅料及包装材料采购主要根据公司生产和销售计划", "小麦采取", "高粱采取");
        assertThat(result.paragraphs().get(0).sourceIds()).containsExactly(operations.sourceId());
    }
    @Test void distinctReviewedExcerptsKeepPlanLanguageWithoutDuplicateModelParaphrases() {
        var reply = new ResearchReply(List.of(paragraph("已经完成。", channels.sourceId(), operations.sourceId()),
                paragraph("再次概述。", channels.sourceId())), List.of("订单数字仍缺失"));
        var result = ResearchReportAnswerSupport.ground(reply, Map.of(channels.sourceId(), channels, operations.sourceId(), operations));
        assertThat(result.paragraphs()).hasSize(2);
        assertThat(result.paragraphs().get(0).text()).endsWith(channels.reportPassage().text()).contains("将构建", "未来将").doesNotContain("已经完成");
        assertThat(result.missingInformation()).containsExactly("订单数字仍缺失");
    }
    @Test void opinionAndFinancialInterpretationsWithoutReportCitationsKeepTheirContent() {
        var original = paragraph("成员猜测不是事实，营业收入不能证明新订单增长。", "op-1", "financial-1");
        var reply = new ResearchReply(List.of(original, paragraph("报告说明。", operations.sourceId())), List.of());
        assertThat(ResearchReportAnswerSupport.ground(reply, Map.of(operations.sourceId(), operations)).paragraphs().get(0)).isSameAs(original);
    }
    @Test void mixedParagraphsAreRejectedRatherThanDroppingTheirOtherEvidence() {
        var reply = new ResearchReply(List.of(paragraph("混合材料。", operations.sourceId(), "financial-1")), List.of());
        assertThatIllegalArgumentException().isThrownBy(() -> ResearchReportAnswerSupport.ground(reply, Map.of(operations.sourceId(), operations)));
    }
}
