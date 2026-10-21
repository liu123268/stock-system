package com.stock.system.agent;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.agent.service.ResearchSummaryModel;
import com.stock.system.agent.service.ResearchSummaryPrompt;
import com.stock.system.agent.service.ResearchSummaryService;
import com.stock.system.agent.service.ResearchSummaryValidator;
import com.stock.system.agent.service.ResearchMarketDataService;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.service.StockResearchSourceService;
import com.stock.system.stock.dto.ResearchSourceFilter;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 内存替身检查业务决策，不连接数据库，也不依赖 JVM 动态代理加载。 */
class ResearchSummaryServiceTest {
    private final List<String> steps = new ArrayList<>();
    private final Sources sources = new Sources();
    private final Model model = new Model();
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final ResearchSummaryPrompt prompt = new ResearchSummaryPrompt(JsonMapper.builder().findAndAddModules().build());
    private ResearchMarketDataService.Quote quoteSnapshot = new ResearchMarketDataService.Quote("UNAVAILABLE", "SAMPLE.HK", null, "HKD", null, null, true,
            "测试样本没有报价");
    private final ResearchSummaryService service = new ResearchSummaryService(sources, prompt,
            new ResearchSummaryValidator(), beans.getBeanProvider(ResearchSummaryModel.class),
            new ResearchMarketDataService(null, null, null, null) {
                @Override
                public Quote readLatestQuote(Long userId, Long workspaceId, Long stockId) {
                    return quoteSnapshot;
                }
            });

    @BeforeEach
    void enableInMemoryModel() { beans.registerSingleton("summaryModel", model); }

    @Test
    void connectsAuthorizedMaterialsMessagesModelValidationAndFreshAccessCheck() {
        var result = service.summarize(10L, 1L, 20L, 30L);
        assertThat(result.summary()).isEqualTo(ResearchSummaryFixtures.summary());
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.quote().status()).isEqualTo("UNAVAILABLE");
        assertThat(result.sources()).extracting(ResearchSummaryPrompt.SourceBinding::authorId)
                .containsExactly("M1", "M1", "M2");
        assertThat(steps).containsExactly("read", "model", "revalidate");
        assertThat(model.input.system()).contains(ResearchSummaryPrompt.ANSWER_RULE);
        assertThat(model.input.user()).contains("认为新订单可能增长")
                .doesNotContain("authorUserId", "workspaceId");
        assertThat(model.calls).isEqualTo(1);
    }

    @Test void modelReceivesTheSameAvailableQuoteShownWithTheFinalSummary() {
        quoteSnapshot = new ResearchMarketDataService.Quote("AVAILABLE", "SAMPLE.HK", new java.math.BigDecimal("612"), "HKD",
                ResearchSummaryFixtures.DATE, "LOCAL_MOCK", true, null);
        var result = service.summarize(10L, 1L, 20L, 30L);
        assertThat(result.quote()).isSameAs(quoteSnapshot);
        var quote = JsonMapper.builder().findAndAddModules().build().readTree(model.input.user().substring(model.input.user().indexOf('{'))).path("quote");
        assertThat(quote.path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(quote.path("price").asInt()).isEqualTo(612);
        assertThat(quote.path("simulated").asBoolean()).isTrue();
        assertThat(model.input.system()).contains("不能因此写", "编号只放在authorId和sourceIds结构字段").doesNotContain("没有提供公告、财报、新闻或行情资料");
    }

    @Test void unavailableQuoteIsAlsoExplicitInModelInputInsteadOfAssumingItsAbsence() {
        service.summarize(10L, 1L, 20L, 30L);
        assertThat(model.input.user()).contains("\"status\":\"UNAVAILABLE\"", "测试样本没有报价");
    }

    @Test
    void filteredSummarySendsSelectedDatesAndAliasWithoutSendingAccountIdentifier() {
        var original = ResearchSummaryFixtures.sources();
        var filter = new ResearchSourceFilter(11L, ResearchSummaryFixtures.DATE.toLocalDate(),
                ResearchSummaryFixtures.DATE.plusDays(1).toLocalDate());
        sources.snapshot = new ResearchCycleSourcesResponseVO(original.workspaceStockId(), original.stockCode(),
                original.stockName(), original.researchCycleId(), original.cycleStatus(), original.startedAt(),
                original.endedAt(), new ResearchCycleSourcesResponseVO.Scope(original.scope().readAt(), 2, 30, 2,
                ResearchCycleSourcesResponseVO.Coverage.COMPLETE, 100, 20_000, filter), original.opinions().subList(0, 2));
        var complete = ResearchSummaryFixtures.summary();
        model.summary = new ResearchSummary(new ResearchSummary.Item("成员甲转为等待。", List.of("op-2")),
                complete.changes(), List.of(complete.members().get(0)), complete.pendingItems());
        var result = service.summarize(10L, 1L, 20L, 30L, filter);
        assertThat(result.scope().filter()).isEqualTo(filter);
        assertThat(model.input.user()).contains("2026-10-13", "2026-10-14", "\"authorId\":\"M1\"")
                .doesNotContain("authorUserId", "成员乙");
    }

    @Test
    void rejectsUnauthorizedReadBeforeObtainingModel() {
        sources.readFailure = ErrorCode.FORBIDDEN;
        rejects(ErrorCode.FORBIDDEN);
        assertThat(steps).containsExactly("read");
        assertThat(model.calls).isZero();
    }

    @Test
    void disabledModelDoesNotSendMaterials() {
        beans.destroySingleton("summaryModel");
        rejects(ErrorCode.RESEARCH_AGENT_DISABLED);
        assertThat(model.calls).isZero();
    }

    @Test
    void excessiveScopeDoesNotCallModel() {
        var original = ResearchSummaryFixtures.sources();
        sources.snapshot = new ResearchCycleSourcesResponseVO(original.workspaceStockId(),
                original.stockCode(), original.stockName(), original.researchCycleId(), original.cycleStatus(),
                original.startedAt(), original.endedAt(), new ResearchCycleSourcesResponseVO.Scope(
                original.scope().readAt(), 101, 1000, 0,
                ResearchCycleSourcesResponseVO.Coverage.LIMIT_EXCEEDED, 100, 20_000), List.of());
        rejects(ErrorCode.RESEARCH_AGENT_SOURCE_LIMIT_EXCEEDED);
        assertThat(model.calls).isZero();
    }

    @Test
    void emptySourcesReturnInsufficiencyWithoutModelRequestAndStillRevalidate() {
        sources.snapshot = ResearchSummaryFixtures.sources(List.of());
        var result = service.summarize(10L, 1L, 20L, 30L);
        assertThat(result.summary().overview().text()).contains("没有有效观点");
        assertThat(result.summary().members()).isEmpty();
        assertThat(result.inputTokens()).isZero();
        assertThat(steps).containsExactly("read", "revalidate");
        assertThat(model.calls).isZero();
    }

    @Test
    void permissionLossOrWithdrawalAfterGenerationPreventsDeliveringAnswer() {
        sources.recheckFailure = ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED;
        rejects(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
        assertThat(model.calls).isEqualTo(1);
        sources.recheckFailure = ErrorCode.WORKSPACE_ACCESS_DENIED;
        rejects(ErrorCode.WORKSPACE_ACCESS_DENIED);
    }

    @Test
    void newOpinionIsFlaggedWithoutAnotherModelRequest() {
        sources.newOpinionsAvailable = true;
        assertThat(service.summarize(10L, 1L, 20L, 30L).newOpinionsAvailable()).isTrue();
        assertThat(model.calls).isEqualTo(1);
    }

    @Test
    void invalidModelSourceCannotBecomeCompletedAnswer() {
        var correct = ResearchSummaryFixtures.summary();
        model.summary = new ResearchSummary(new ResearchSummary.Item("虚构依据", List.of("op-999")),
                correct.changes(), correct.members(), correct.pendingItems());
        rejects(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
    }

    @Test
    void serializedUnicodeOrEscapesOverMessageLimitAreRejectedBeforeNetwork() {
        sources.snapshot = ResearchSummaryFixtures.sources(List.of(ResearchSummaryFixtures.opinion(
                1L, 11L, "甲", "NEUTRAL", "\u0001".repeat(12_000), ResearchSummaryFixtures.DATE)));
        rejects(ErrorCode.RESEARCH_AGENT_SOURCE_LIMIT_EXCEEDED);
        assertThat(model.calls).isZero();
    }

    private void rejects(ErrorCode code) {
        assertThatThrownBy(() -> service.summarize(10L, 1L, 20L, 30L))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }

    private class Sources extends StockResearchSourceService {
        ResearchCycleSourcesResponseVO snapshot = ResearchSummaryFixtures.sources();
        ErrorCode readFailure;
        ErrorCode recheckFailure;
        boolean newOpinionsAvailable;

        Sources() { super(null, null, null, null); }

        @Override
        public ResearchCycleSourcesResponseVO readCycle(Long user, Long workspace, Long stock, Long cycle) {
            return readSnapshot(user, workspace, stock, cycle);
        }

        private ResearchCycleSourcesResponseVO readSnapshot(Long user, Long workspace, Long stock, Long cycle) {
            assertThat(List.of(user, workspace, stock, cycle)).containsExactly(10L, 1L, 20L, 30L);
            steps.add("read");
            if (readFailure != null) throw new BusinessException(readFailure);
            return snapshot;
        }

        @Override
        public ResearchCycleSourcesResponseVO readCycle(Long user, Long workspace, Long stock, Long cycle,
                                                        ResearchSourceFilter filter) {
            assertThat(filter).isEqualTo(snapshot.scope().filter());
            return readSnapshot(user, workspace, stock, cycle);
        }

        @Override
        public SourceValidity revalidate(Long user, Long workspace, Long stock, Long cycle,
                                         ResearchCycleSourcesResponseVO expected) {
            assertThat(expected).isSameAs(snapshot);
            steps.add("revalidate");
            if (recheckFailure != null) throw new BusinessException(recheckFailure);
            return new SourceValidity(newOpinionsAvailable);
        }
    }

    private class Model extends ResearchSummaryModel {
        int calls;
        ResearchSummaryPrompt.Input input;
        ResearchSummary summary = ResearchSummaryFixtures.summary();

        Model() { super(ChatClient.builder(unused -> { throw new AssertionError("Unexpected transport call"); })); }

        @Override
        public Result generate(ResearchSummaryPrompt.Input input) {
            calls++;
            steps.add("model");
            this.input = input;
            return new Result(summary, "offline-model", 100, 80);
        }
    }
}
