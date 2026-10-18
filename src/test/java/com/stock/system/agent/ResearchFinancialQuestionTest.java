package com.stock.system.agent;

import com.stock.system.agent.dto.ResearchReply;
import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.service.*;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.financial.*;
import com.stock.system.stock.service.StockFinancialService;
import com.stock.system.stock.service.StockResearchSourceService;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native messages and reviewed report data, with no HTTP model, provider or database calls. */
class ResearchFinancialQuestionTest {
    private final StockResearchSourceService sources = mock(StockResearchSourceService.class);
    private final ResearchMarketDataService market = mock(ResearchMarketDataService.class);
    private final StockFinancialService financials = mock(StockFinancialService.class);
    private final ResearchReportService reports = mock(ResearchReportService.class);
    private final ResearchQuestionModel model = mock(ResearchQuestionModel.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<@NonNull ResearchQuestionModel> models = mock(ObjectProvider.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ResearchQuestionService questions = new ResearchQuestionService(sources, market, models, json, financials, reports);
    private final Target target = new Target(1L, 2L, 20L, 30L);
    private final FinancialFacts facts = new OfficialFinancialReportCatalog().read("600519.SH", FinancialPeriod.ANNUAL,
            "FY 2025", List.of(FinancialMetric.REVENUE, FinancialMetric.NET_PROFIT));
    private final List<String> toolResponses = new ArrayList<>();
    private ResearchCycleSourcesResponseVO material;

    @BeforeEach void setUp() {
        var original = ResearchSummaryFixtures.sources();
        material = new ResearchCycleSourcesResponseVO(original.workspaceStockId(), "600519.SH", "贵州茅台", original.researchCycleId(),
                original.cycleStatus(), original.startedAt(), original.endedAt(), original.scope(), original.opinions());
        when(models.getIfAvailable()).thenReturn(model);
        when(sources.revalidate(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new StockResearchSourceService.SourceValidity(false));
        when(sources.readCycle(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(material);
        when(financials.read(anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(facts);
    }

    @Test void sendsVerifiedFinancialSnapshotAndKeepsSourcePagesAndJavaChanges() {
        respondWith(List.of(call("f1", "read_financial_facts", "{\"series\":\"ANNUAL\",\"period\":\"FY 2025\",\"metrics\":[\"REVENUE\",\"NET_PROFIT\"]}")));
        var answer = answer();
        verify(financials).read(1L, 2L, 20L, FinancialPeriod.ANNUAL, "FY 2025", List.of(FinancialMetric.REVENUE, FinancialMetric.NET_PROFIT));
        assertThat(answer.followUp().evidence()).hasSize(1);
        assertThat(answer.followUp().evidence().get(0).financialFacts()).isEqualTo(facts);
        assertThat(toolResponses.get(0)).contains("168838102514.79", "CNY", "YUAN", "PARENT_SHAREHOLDERS", "pdfPages");
        assertThat(answer.followUp().reply().paragraphs().get(0).sourceIds()).startsWith(answer.followUp().evidence().get(0).sourceId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"series\":\"ANNUAL\",\"symbol\":\"700.HK\"}",
            "{\"series\":\"ANNUAL\",\"workspaceId\":999}",
            "{\"series\":\"ANNUAL\",\"url\":\"https://untrusted.invalid\"}",
            "{\"series\":\"EVERYTHING\"}", "{}"
    })
    void rejectsModelSuppliedObjectsUrlsAndUnknownPeriodsBeforeReading(String arguments) {
        respondWith(List.of(call("f1", "read_financial_facts", arguments)));
        assertThat(answer().followUp().evidence()).isEmpty();
        verifyNoInteractions(financials);
        assertThat(toolResponses.get(0)).contains("INVALID_TOOL_ARGUMENTS");
    }

    @Test void unverifiedProviderAmountsNeverReachTheModelOrFinancialCitations() {
        var current = facts.facts().get(0).current();
        var unknown = new FinancialFacts.Value(current.period(), current.series(), current.year(), current.part(), current.periodEnd(),
                new BigDecimal("123456789.99"), "HKD", "UNKNOWN", "UNKNOWN", "PROVIDER_DISPLAY_UNVERIFIED");
        var partial = new FinancialFacts("PARTIAL", "600519.SH", FinancialPeriod.ANNUAL, "FY 2025", List.of("FY 2025"),
                List.of(new FinancialFacts.Fact(FinancialMetric.REVENUE, "OperatingRevenue", "源端数字", unknown, null,
                        new FinancialFacts.Change(null, null, "CURRENCY_CONVERSION_UNVERIFIED"))), List.of(),
                "LONGBRIDGE_FINANCIAL_REPORT", java.time.Instant.now(), null, "SOURCE_BASIS_UNVERIFIED", "单位未核实");
        when(financials.read(anyLong(), anyLong(), anyLong(), any(), any(), any())).thenReturn(partial);
        respondWith(List.of(call("f1", "read_financial_facts", "{\"series\":\"ANNUAL\"}")));
        assertThat(answer().followUp().evidence()).isEmpty();
        assertThat(toolResponses.get(0)).doesNotContain("123456789.99").contains("SOURCE_BASIS_UNVERIFIED");
    }

    @Test void threeToolsCanCombineOpinionsQuoteAndFinancialFactsWithoutExceedingTheBound() {
        when(market.readLatestQuote(1L, 2L, 20L)).thenReturn(new ResearchMarketDataService.Quote("AVAILABLE", "600519.SH",
                new BigDecimal("123"), "CNY", java.time.LocalDateTime.now(), "MOCK", true, null));
        respondWith(List.of(call("o1", "read_cycle_opinions", "{}"), call("q1", "read_latest_quote", "{}"),
                call("f1", "read_financial_facts", "{\"series\":\"ANNUAL\"}")));
        var answer = answer();
        assertThat(toolResponses).hasSize(3);
        assertThat(answer.followUp().evidence()).extracting(source -> source.type()).containsExactly("QUOTE", "FINANCIAL");
        assertThat(answer.references()).hasSize(3);
    }

    @Test void fourthToolDoesNotReadAndReturnsTheLimitWithTheExistingEvidence() {
        respondWith(List.of(call("f1", "read_financial_facts", "{\"series\":\"ANNUAL\"}"),
                call("f2", "read_financial_facts", "{\"series\":\"ANNUAL\"}"),
                call("f3", "read_financial_facts", "{\"series\":\"ANNUAL\"}"),
                call("f4", "read_financial_facts", "{\"series\":\"ANNUAL\"}")));
        assertThat(answer().followUp().evidence()).hasSize(3);
        verify(financials, times(3)).read(anyLong(), anyLong(), anyLong(), any(), any(), any());
        assertThat(toolResponses.get(3)).contains("TOOL_LIMIT");
    }

    @Test void reportToolKeepsCanonicalTextPositionAndCitationsInSavedJson() throws Exception {
        var passage = new com.stock.system.agent.report.ReportCatalog().forSymbol("600519.SH").get(0);
        when(reports.search(target, "公司主要业务", 2025)).thenReturn(new ResearchReportService.Search("AVAILABLE", List.of(passage), "仅为已核对章节"));
        respondWith(List.of(call("r1", "search_report_passages", "{\"query\":\"公司主要业务\",\"year\":2025}")));
        var answer = answer();
        assertThat(answer.followUp().reply().paragraphs().get(0).sourceIds()).containsExactly("report-" + passage.id());
        assertThat(answer.followUp().evidence().get(0).reportPassage()).isEqualTo(passage);
        var restored = json.readValue(json.writeValueAsString(answer), com.stock.system.agent.vo.ResearchAgentResponseVO.Answer.class);
        questions.verifyEvidence(target, restored.followUp().evidence());
        verify(reports, atLeastOnce()).verify(target, passage);
    }
    @ParameterizedTest
    @ValueSource(strings = {"{\"query\":\"主营业务\",\"symbol\":\"00700.HK\"}", "{\"query\":\"主营业务\",\"url\":\"https://other.invalid\"}"})
    void reportArgumentsCannotChooseAnotherStockOrUrl(String args) {
        respondWith(List.of(call("r1", "search_report_passages", args)));
        assertThat(answer().followUp().evidence()).isEmpty();
        verifyNoInteractions(reports);
        assertThat(toolResponses.get(0)).contains("INVALID_TOOL_ARGUMENTS");
    }

    @Test void withdrawnReportVersionStopsGenerationBeforeAnotherModelRequest() {
        doNothing().doThrow(new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED))
                .when(financials).verifySnapshot(anyLong(), anyLong(), anyLong(), any());
        respondWith(List.of(call("f1", "read_financial_facts", "{\"series\":\"ANNUAL\"}")));
        assertThatThrownBy(this::answer).isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
        verify(model).call(anyList(), anyList());
    }

    private com.stock.system.agent.vo.ResearchAgentResponseVO.Answer answer() {
        try (var execution = new ResearchModelExecution(ResearchQuestionService.MAX_MODEL_CALLS)) {
            var result = questions.answer(questions.prepare(target, material, List.of(), false), "请核对这项判断与财务资料", () -> {});
            assertThat(execution.toolCalls).isLessThanOrEqualTo(ResearchQuestionService.MAX_TOOL_ATTEMPTS);
            return result;
        }
    }
    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }
    private void respondWith(List<AssistantMessage.ToolCall> calls) {
        when(model.call(anyList(), anyList())).thenAnswer(invocation -> {
            var execution = ResearchModelExecution.current();
            execution.dispatch(); execution.usage("OFFLINE_TEST", 10, 10);
            if (execution.modelCalls == 1) return AssistantMessage.builder().toolCalls(calls).build();
            List<Message> messages = invocation.getArgument(0);
            var response = messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .reduce((first, second) -> second).orElseThrow();
            var ids = new ArrayList<String>();
            for (var item : response.getResponses()) {
                toolResponses.add(item.responseData());
                var data = json.readTree(item.responseData());
                if (data.has("sourceId")) ids.add(data.path("sourceId").asString());
                else if (data.has("passages") && !data.path("passages").isEmpty()) ids.add(data.path("passages").get(0).path("sourceId").asString());
                else if (data.has("opinions")) ids.add(data.path("opinions").get(0).path("sourceId").asString());
            }
            return new AssistantMessage(json.writeValueAsString(new ResearchReply(
                    List.of(new ResearchSummary.Item("这是基于已取得资料的核对结果。", ids)), List.of())));
        });
    }
}
