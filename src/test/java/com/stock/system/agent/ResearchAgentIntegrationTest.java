package com.stock.system.agent;

import com.stock.system.agent.dto.ResearchSummary;
import com.stock.system.agent.mapper.ResearchAgentRecordMapper;
import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.service.*;
import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.dto.ResearchSourceFilter;
import com.stock.system.stock.service.StockResearchService;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.service.WorkspaceService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真 MySQL／认证／会话／并发，模型为受控替身，不发送真实付费请求。 */
@SpringBootTest(properties = "stock-system.agent.budget-yuan=10")
@AutoConfigureMockMvc
class ResearchAgentIntegrationTest extends MySqlIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired UserService users;
    @Autowired WorkspaceService workspaces;
    @Autowired StockResearchService research;
    @Autowired ResearchAgentService agent;
    @Autowired ResearchAgentRecordMapper records;
    @Autowired MockMvc mvc;
    @MockitoBean ResearchSummaryModel model;
    final List<Long> userIds = new ArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> mode = new AtomicReference<>("valid");
    String ownerToken;
    String memberToken;
    Target owner;
    Target member;
    long opinion;
    long catalogStock;
    boolean createdStock;
    boolean createdBudget;
    CountDownLatch entered;
    CountDownLatch release;
    ExecutorService executor;
    Long fixtureWorkspace;

    @BeforeEach void fixture() {
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);
        createdBudget = jdbc.queryForObject("select count(*) from agent_budget", Long.class) == 0;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 9);
        ownerToken = register("ago_" + suffix);
        long ownerId = userIds.get(0);
        memberToken = register("agm_" + suffix);
        long memberId = userIds.get(1);
        long workspace = workspaces.create(ownerId, "研究助手" + suffix).workspaceId();
        fixtureWorkspace = workspace;
        jdbc.update("insert into workspace_member(workspace_id, user_id, status) values (?, ?, 'ACTIVE')",
                workspace, memberId);
        createdStock = jdbc.queryForObject("select count(*) from stock where code = '00700.HK'", Long.class) == 0;
        var added = research.add(ownerId, workspace, new AddWorkspaceStockRequestDTO("00700.HK", "BULLISH",
                "成员猜测订单可能增加", "需要公告核对", "MEDIUM", null, null));
        owner = new Target(ownerId, workspace, added.workspaceStockId(), added.researchCycleId());
        member = new Target(memberId, workspace, owner.stock(), owner.cycle());
        catalogStock = jdbc.queryForObject("select stock_id from workspace_stock where id = ?", Long.class, owner.stock());
        opinion = jdbc.queryForObject("select id from stock_opinion where research_cycle_id = ?", Long.class, owner.cycle());
        calls.set(0);
        mode.set("valid");
        doAnswer(invocation -> {
            calls.incrementAndGet();
            var execution = ResearchModelExecution.current();
            execution.dispatched = true;
            entered.countDown();
            if (mode.get().equals("blocked")) assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
            if (mode.get().equals("unknown")) throw new BusinessException(ErrorCode.RESEARCH_AGENT_UNAVAILABLE);
            execution.usage("offline-agent-test", 100, 50);
            if (mode.get().equals("invalid")) throw new BusinessException(ErrorCode.RESEARCH_AGENT_INVALID_RESULT);
            var input = (ResearchSummaryPrompt.Input) invocation.getArgument(0);
            var refs = input.sources().stream().map(ResearchSummaryPrompt.SourceBinding::sourceId).toList();
            var latestByAuthor = new LinkedHashMap<String, ResearchSummaryPrompt.SourceBinding>();
            input.sources().forEach(source -> latestByAuthor.put(source.authorId(), source));
            var members = latestByAuthor.values().stream().map(source -> new ResearchSummary.Member(source.authorId(),
                    source.opinion().authorNickname() + "认为订单可能增加，尚需权威资料核对。", List.of(source.sourceId()))).toList();
            var summary = new ResearchSummary(new ResearchSummary.Item("成员提出可能增长的判断，当前没有权威资料确认。", refs),
                    List.of(), members, List.of(new ResearchSummary.Item("缺少公告或财报，无法确认实际增长。", refs)));
            return new ResearchSummaryModel.Result(summary, "offline-agent-test", 100, 50);
        }).when(model).generate(any());
    }

    @Test void getDoesNotCreateAndGenerationRestoresPrivateResultWithIndependentLedger() throws Exception {
        assertThat(agent.restore(owner, null).messages()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from agent_session where user_id = ?", Long.class, owner.user())).isZero();
        String requestId = UUID.randomUUID().toString();
        var result = agent.summarize(owner, requestId, ResearchSourceFilter.fullCycle());
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.answer().references().get(0).opinionId()).isEqualTo(opinion);
        assertThat(agent.restore(owner, null).messages()).hasSize(1);
        assertThat(agent.restore(member, null).messages()).isEmpty();
        assertThat(agent.summarize(owner, requestId, ResearchSourceFilter.fullCycle()).runId()).isEqualTo(result.runId());
        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id = ?", BigDecimal.class, result.runId()))
                .isEqualByComparingTo("0.0006");
        mvc.perform(get(base() + "/runs/" + result.runId()).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESEARCH_AGENT_RUN_NOT_FOUND"));
        mvc.perform(get(base()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.messages[0].answer.summary.overview.text").exists())
                .andExpect(jsonPath("$.data.messages[0].answer.references[0].opinion").doesNotExist());
        agent.clear(owner);
        assertThat(agent.restore(owner, null).messages()).isEmpty();
        assertThat(agent.status(owner, result.runId()).status()).isEqualTo("CLEARED");
        assertThat(jdbc.queryForObject("select count(*) from agent_run where id = ?", Long.class, result.runId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from stock_opinion where id = ?", Long.class, opinion)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id = ?", BigDecimal.class, result.runId()))
                .isEqualByComparingTo("0.0006");
    }

    @Test void changedPayloadReusingRequestIdIsRejectedWithoutModelCall() {
        String id = UUID.randomUUID().toString();
        agent.summarize(owner, id, ResearchSourceFilter.fullCycle());
        assertThatThrownBy(() -> agent.summarize(owner, id, new ResearchSourceFilter(owner.user(), null, null)))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_REQUEST_CONFLICT));
        assertThat(calls).hasValue(1);
    }

    @Test void withdrawalHidesEntireOldAnswerAndAddingNewOpinionsOnlyFlagsRefresh() {
        var result = generate();
        jdbc.update("insert into stock_opinion(research_cycle_id, author_user_id, type, stance, reason, observation_note, horizon)"
                + " values (?, ?, 'UPDATE', 'NEUTRAL', '新判断', null, 'MEDIUM')", owner.cycle(), owner.user());
        assertThat(agent.restore(owner, null).messages().get(0).answer().newOpinionsAvailable()).isTrue();
        assertThat(calls).hasValue(1);
        jdbc.update("update stock_opinion set withdrawn_at = current_timestamp(3), withdrawn_by_user_id = author_user_id where id = ?", opinion);
        var invalidated = agent.status(owner, result.runId());
        assertThat(invalidated.status()).isEqualTo("INVALIDATED");
        assertThat(invalidated.answer()).isNull();
        assertThat(agent.restore(owner, null).invalidated()).isTrue();
    }

    @Test void invalidAnswerStillRecordsKnownUsageAndUnknownFailureKeepsReservation() {
        mode.set("invalid");
        var invalid = generate();
        assertThat(invalid.status()).isEqualTo("FAILED");
        assertThat(invalid.answer()).isNull();
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id = ?", BigDecimal.class, invalid.runId()))
                .isEqualByComparingTo("0.0006");
        mode.set("unknown");
        var unknown = generate();
        assertThat(unknown.errorCode()).isEqualTo("RESEARCH_AGENT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id = ?", BigDecimal.class, unknown.runId())).isNull();
        assertThat(jdbc.queryForObject("select reserved_yuan from agent_run where id = ?", BigDecimal.class, unknown.runId()))
                .isEqualByComparingTo(ResearchAgentRecordMapper.RESERVE);
        assertThat(calls).hasValue(2);
    }

    @Test void budgetStopsNextCallBeforeDispatchAndClearDoesNotResetUnknownCosts() {
        mode.set("unknown");
        generate(); generate();
        agent.clear(owner);
        assertThatThrownBy(this::generate).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_BUDGET_EXCEEDED));
        assertThat(calls).hasValue(2);
    }

    @Test void simultaneousSameRequestReusesRunningStateAndClearBlocksLateSave() throws Exception {
        mode.set("blocked");
        executor = Executors.newSingleThreadExecutor();
        String id = UUID.randomUUID().toString();
        var running = executor.submit(() -> agent.summarize(owner, id, ResearchSourceFilter.fullCycle()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(agent.summarize(owner, id, ResearchSourceFilter.fullCycle()).status()).isEqualTo("RUNNING");
        assertThatThrownBy(this::generate).isInstanceOfSatisfying(BusinessException.class, failure ->
                assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_BUSY));
        agent.clear(owner);
        release.countDown();
        assertThat(running.get(6, TimeUnit.SECONDS).status()).isEqualTo("CLEARED");
        assertThat(agent.restore(owner, null).messages()).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test void losingMembershipRejectsOldMessagesAndCrossCycleRequestsAreRejected() throws Exception {
        agent.summarize(member, UUID.randomUUID().toString(), ResearchSourceFilter.fullCycle());
        jdbc.update("update workspace_member set status = 'LEFT', last_inactive_at = current_timestamp(3) where workspace_id = ? and user_id = ?", member.workspace(), member.user());
        assertThatThrownBy(() -> agent.restore(member, null)).isInstanceOf(BusinessException.class);
        mvc.perform(get(base()).header("Authorization", "Bearer " + memberToken)).andExpect(status().isForbidden());
        mvc.perform(get(base().replace("/cycles/" + owner.cycle(), "/cycles/999999999"))
                .header("Authorization", "Bearer " + ownerToken)).andExpect(status().isNotFound());
        mvc.perform(get(base())).andExpect(status().isUnauthorized());
    }

    @Test void emptyFilteredScopeCompletesWithoutSpendingAndInvalidInputCreatesNoRun() throws Exception {
        var empty = agent.summarize(owner, UUID.randomUUID().toString(), new ResearchSourceFilter(null,
                java.time.LocalDate.now().plusDays(1), java.time.LocalDate.now().plusDays(1)));
        assertThat(empty.status()).isEqualTo("SUCCEEDED");
        assertThat(empty.answer().summary().overview().text()).contains("没有有效观点");
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id = ?", BigDecimal.class, empty.runId())).isZero();
        mvc.perform(post(base() + "/summaries").header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").content("{\"requestId\":\"bad\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void interruptedRunRecoveryKeepsUnknownReservationWithoutResendingModel() {
        var started = records.begin(owner, UUID.randomUUID().toString(), "0".repeat(64), true);
        jdbc.update("update agent_run set started_at = current_timestamp(3) - interval 130 second, dispatched = true where id = ?",
                started.run().id());
        var state = agent.status(owner, started.run().id());
        assertThat(state.status()).isEqualTo("INTERRUPTED");
        assertThat(state.answer()).isNull();
        assertThat(agent.restore(owner, null).activeRun()).isNull();
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id = ?", BigDecimal.class, state.runId())).isNull();
    }

    @Test void globalConcurrencyTwoIsEnforcedAcrossDifferentUsers() {
        String token = register("agt_" + UUID.randomUUID().toString().substring(0, 9).replace("-", "a"));
        long thirdId = userIds.get(2);
        jdbc.update("insert into workspace_member(workspace_id, user_id, status) values (?, ?, 'ACTIVE')", owner.workspace(), thirdId);
        Target third = new Target(thirdId, owner.workspace(), owner.stock(), owner.cycle());
        var first = records.begin(owner, UUID.randomUUID().toString(), "0".repeat(64), true);
        var second = records.begin(member, UUID.randomUUID().toString(), "1".repeat(64), true);
        try {
            assertThatThrownBy(() -> records.begin(third, UUID.randomUUID().toString(), "2".repeat(64), true))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_BUSY));
            assertThat(calls).hasValue(0);
        } finally {
            for (var item : List.of(first, second)) {
                try (var execution = new ResearchModelExecution()) {
                    records.finish(item == first ? owner : member, item.run(), execution, null, null,
                            ErrorCode.RESEARCH_AGENT_UNAVAILABLE, () -> null);
                }
            }
        }
    }

    private com.stock.system.agent.vo.ResearchAgentResponseVO.Run generate() {
        return agent.summarize(owner, UUID.randomUUID().toString(), ResearchSourceFilter.fullCycle());
    }
    private String base() { return "/api/workspaces/" + owner.workspace() + "/stocks/" + owner.stock()
            + "/research/cycles/" + owner.cycle() + "/agent"; }
    private String register(String name) {
        auth.register(new RegisterRequestDTO(name, "TestPass123", name));
        userIds.add(users.findByUsername(name).getId());
        return auth.login(new LoginRequestDTO(name, "TestPass123")).accessToken();
    }

    @AfterEach void cleanup() throws Exception {
        if (release != null) release.countDown();
        if (executor != null) { executor.shutdown(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        if (owner != null) {
            BigDecimal ownCost = jdbc.queryForObject("select coalesce(sum(coalesce(cost_upper_yuan, reserved_yuan)), 0)"
                    + " from agent_run where user_id in (?, ?)", BigDecimal.class, owner.user(), member.user());
            jdbc.update("delete m from agent_message m join agent_session s on s.id = m.session_id where s.workspace_id = ?", owner.workspace());
            jdbc.update("delete from agent_run where user_id in (?, ?)", owner.user(), member.user());
            jdbc.update("delete from agent_session where workspace_id = ?", owner.workspace());
            jdbc.update("update agent_budget set occupied_yuan = occupied_yuan - ? where id = 1", ownCost);
            if (createdBudget) jdbc.update("delete from agent_budget where id = 1 and occupied_yuan = 0"
                    + " and not exists(select 1 from agent_run)");
            jdbc.update("delete from research_attention_change where research_cycle_id = ?", owner.cycle());
            jdbc.update("delete from stock_opinion where research_cycle_id = ?", owner.cycle());
            jdbc.update("delete from research_cycle where id = ?", owner.cycle());
            jdbc.update("delete from workspace_stock where id = ?", owner.stock());
            jdbc.update("delete from workspace_member where workspace_id = ?", owner.workspace());
            jdbc.update("delete from workspace where id = ?", owner.workspace());
            if (createdStock) jdbc.update("delete from stock where id = ? and not exists(select 1 from workspace_stock where stock_id = ?)", catalogStock, catalogStock);
            for (String table : List.of("agent_session", "workspace_member", "workspace_stock"))
                assertThat(jdbc.queryForObject("select count(*) from " + table + " where workspace_id = ?", Long.class, owner.workspace())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from agent_run where user_id in (?, ?)", Long.class, owner.user(), member.user())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from stock_opinion where research_cycle_id = ?", Long.class, owner.cycle())).isZero();
        }
        if (fixtureWorkspace != null && owner == null) {
            jdbc.update("delete from workspace_member where workspace_id = ?", fixtureWorkspace);
            jdbc.update("delete from workspace where id = ?", fixtureWorkspace);
        }
        for (long id : userIds) {
            jdbc.update("delete from app_user where id = ?", id);
            assertThat(jdbc.queryForObject("select count(*) from app_user where id = ?", Long.class, id)).isZero();
        }
    }
}
