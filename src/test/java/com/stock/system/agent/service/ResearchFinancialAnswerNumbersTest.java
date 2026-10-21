package com.stock.system.agent.service;

import com.stock.system.agent.report.ReportCatalog;
import com.stock.system.agent.vo.ResearchAgentResponseVO.Evidence;
import com.stock.system.stock.financial.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ResearchFinancialAnswerNumbersTest {
    private final OfficialFinancialReportCatalog catalog = new OfficialFinancialReportCatalog();
    private final Evidence moutai = evidence("600519.SH");
    private Evidence evidence(String symbol) {
        return new Evidence("financial-test", "FINANCIAL", "财务依据", null, null,
                catalog.read(symbol, FinancialPeriod.ANNUAL, "FY 2025", List.of(FinancialMetric.REVENUE, FinancialMetric.NET_PROFIT)));
    }
    @Test void preservesExactOriginalAmountsDatesAndJavaChangesWithGrouping() {
        assertThatCode(() -> check("2025年度营业收入168,838,102,514.79元人民币，2024年度170,899,152,276.34元，"
                + "减少2,061,049,761.55元，同比减少1.2060%。")).doesNotThrowAnyException();
    }
    @Test void rejectsTheActualV6WrongYuanToYiYuanAnswer() {
        assertThatIllegalArgumentException().isThrownBy(() -> check("2025年度营业收入为1688.3810251459亿元，2024年度1708.9915227634亿元。"));
        assertThatIllegalArgumentException().isThrownBy(() -> check("2025年度营业收入为1688.3810251459。"));
    }
    @Test void aMathematicallyCorrectConversionStillNeedsAnExplicitJavaDisplayValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> check("2025年度营业收入为1688.3810251479亿元。"));
        assertThatIllegalArgumentException().isThrownBy(() -> check("2025年度营业收入约1688亿元。"));
    }
    @Test void changingOnlyTheUnitOrCurrencyCannotReuseTheRightNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> check("营业收入为168838102514.79万元。"));
        assertThatIllegalArgumentException().isThrownBy(() -> check("营业收入为HKD168838102514.79元。"));
    }
    @Test void rejectsTheActualV8MissingPercentUnitWithoutRejectingOriginalPercentOrMetadata() {
        assertThatIllegalArgumentException().isThrownBy(() -> check("同比变化为-1.2060（按同一份年度报告的比较列）。"));
        assertThatCode(() -> check("2025年度同比变化为-1.2060%。")).doesNotThrowAnyException();
        assertThatCode(() -> check("同比减少1.2060 ％。")).doesNotThrowAnyException();
    }
    @Test void anotherCitedOriginalMayUseTheSameDigitsForADifferentPurpose() {
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("成员把实验样本编号写作1.2060，收入不能证明订单。",
                List.of(moutai), List.of("实验样本编号1.2060"))).doesNotThrowAnyException();
    }
    @Test void metadataNumbersDoNotAuthorizeAMonetaryOrPercentClaim() {
        assertThatIllegalArgumentException().isThrownBy(() -> check("营业收入为2025元。"));
        assertThatIllegalArgumentException().isThrownBy(() -> check("同比增加2025%。"));
        assertThatIllegalArgumentException().isThrownBy(() -> check("营业收入为1.6883810251479e11元。"));
    }
    @Test void keepsTheTencentOriginalMillionYuanBasis() {
        var tencent = evidence("00700.HK");
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("2025年收入751,766百万元人民币，权益持有人应占盈利224,842百万元人民币。",
                List.of(tencent), List.of())).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> ResearchFinancialAnswerNumbers.validate("2025年收入751,766亿元。", List.of(tencent), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> check("2025年收入751,766百万元。"));
    }
    @Test void describesTheExpectedYearOfAMissingAnnualComparison() {
        var missing = new Evidence("financial-missing", "FINANCIAL", "财务依据", null, null,
                catalog.read("00700.HK", FinancialPeriod.ANNUAL, "FY 2024", List.of(FinancialMetric.REVENUE)));
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("2024年收入660257百万元人民币，本次缺少2023年比较列，不能计算2024年相对2023年的同比。",
                List.of(missing), List.of())).doesNotThrowAnyException();
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("FY 2024收入660257百万元；缺少FY 2023，不能计算所问同比。",
                List.of(missing), List.of())).doesNotThrowAnyException();
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("仅取得2024年、2025年比较列，2023年缺失。",
                List.of(missing), List.of())).doesNotThrowAnyException();
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("FY 2023,2024年无法形成完整比较，2024年收入660,257百万元。",
                List.of(missing), List.of())).doesNotThrowAnyException();
    }
    @ParameterizedTest
    @ValueSource(strings = {"2023百万元人民币", "2023元", "2023%", "缺少2022年比较列", "缺少FY 2022", "未知数字2023", "缺少-2023年比较列", "缺少FY 2023.5", "缺少FY 2023,456"})
    void aMissingComparisonYearDoesNotAuthorizeAmountsPercentagesOrOtherYears(String text) {
        var missing = new Evidence("financial-missing", "FINANCIAL", "财务依据", null, null,
                catalog.read("00700.HK", FinancialPeriod.ANNUAL, "FY 2024", List.of(FinancialMetric.REVENUE)));
        assertThatExceptionOfType(ResearchFinancialAnswerNumbers.Rejected.class).isThrownBy(() ->
                ResearchFinancialAnswerNumbers.validate(text, List.of(missing), List.of()));
    }
    @Test void aPresentComparisonCannotAuthorizeAnUnseenPreviousYear() {
        assertThatExceptionOfType(ResearchFinancialAnswerNumbers.Rejected.class).isThrownBy(() -> check("缺少2023年比较列"))
                .extracting(ResearchFinancialAnswerNumbers.Rejected::rule).isEqualTo(ResearchFinancialAnswerNumbers.Rule.UNSUPPORTED_LITERAL);
    }
    @Test void allowsNumbersQuotedFromTheOtherCitedReport() throws Exception {
        var passage = new ReportCatalog().forSymbol("600519.SH").get(0);
        var report = new Evidence("report-" + passage.id(), "REPORT", "原文", null, null, null, passage);
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("报告称营销网络覆盖66个国家和地区，2025年营业收入168838102514.79元。",
                List.of(moutai, report), List.of())).doesNotThrowAnyException();
    }
    @Test void preservesQuotedOpinionAmountsWithoutTurningThemIntoCompanyFacts() {
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("成员甲2026年10月18日猜测订单300万元，但年度收入168838102514.79元不能证明订单增长。",
                List.of(moutai), List.of("成员甲2026年10月18日猜测订单300万元"))).doesNotThrowAnyException();
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("成员user1e5的猜测不改变营业收入168838102514.79元这一事实。",
                List.of(moutai), List.of("成员user1e5的猜测"))).doesNotThrowAnyException();
    }
    @Test void doesNotApplyFinancialUnitsToAReportOnlyParagraph() throws Exception {
        var passage = new ReportCatalog().forSymbol("00700.HK").stream().filter(row -> row.section().equals("tx-marketing")).findFirst().orElseThrow();
        var report = new Evidence("report-" + passage.id(), "REPORT", "原文", null, null, null, passage);
        assertThatCode(() -> ResearchFinancialAnswerNumbers.validate("报告称营销服务收入1,450亿元，同比增长19%。", List.of(report), List.of())).doesNotThrowAnyException();
    }
    private void check(String text) { ResearchFinancialAnswerNumbers.validate(text, List.of(moutai), List.of()); }
}
