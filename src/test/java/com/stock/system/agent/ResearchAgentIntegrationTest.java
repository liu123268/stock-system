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
import org.springframework.ai.chat.messages.*;
import tools.jackson.databind.ObjectMapper;

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
    @MockitoBean ResearchQuestionModel questionModel;
    @Autowired ObjectMapper json;
    String questionMode = "opinions";
    Runnable afterFirstQuestionResponse = () -> {};
    final List<List<Message>> questionInputs = new ArrayList<>();
    final AtomicInteger questionCalls = new AtomicInteger();
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
            execution.dispatch();
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
        doAnswer(invocation -> {
            questionCalls.incrementAndGet();
            questionInputs.add(List.copyOf(invocation.getArgument(0)));
            var execution = ResearchModelExecution.current();
            execution.dispatch();
            if (questionMode.equals("second-failure") && execution.modelCalls == 2)
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_UNAVAILABLE);
            execution.usage("offline-question-test", 100, 80);
            if (execution.modelCalls == 1) afterFirstQuestionResponse.run();
            if (execution.modelCalls == 1 || questionMode.equals("loop")) {
                String name = questionMode.equals("financial") ? "read_financial_facts"
                        : questionMode.equals("quote") || questionMode.equals("second-failure") || questionMode.equals("loop")
                        ? "read_latest_quote" : questionMode.equals("daily") ? "read_daily_prices" : "read_cycle_opinions";
                String args = name.equals("read_cycle_opinions") ? "{}" : name.equals("read_daily_prices")
                        ? "{\"dateFrom\":\"2026-09-01\",\"dateTo\":\"2026-09-04\"}"
                        : name.equals("read_financial_facts") ? "{\"series\":\"ANNUAL\",\"period\":\"FY 2025\",\"metrics\":[\"REVENUE\",\"NET_PROFIT\"]}" : "{}";
                if (questionMode.equals("invalid-args")) args = "{\"workspaceId\":999,\"authorUserId\":999}";
                return AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall("call-" + execution.modelCalls,
                        "function", name, args))).build();
            }
            List<Message> messages = invocation.getArgument(0);
            var response = messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .reduce((first, second) -> second).orElseThrow().getResponses().get(0);
            var data = json.readTree(response.responseData());
            List<String> refs = questionMode.equals("invalid-args") ? List.of()
                    : questionMode.equals("opinions") ? List.of(data.path("opinions").get(0).path("sourceId").asString())
                    : List.of(data.path("sourceId").asString());
            assertThat(response.responseData()).doesNotContain("authorUserId", "workspaceId");
            if (questionMode.equals("invalid-args")) assertThat(data.path("errorCode").asString()).isEqualTo("INVALID_TOOL_ARGUMENTS");
            return new AssistantMessage(json.writeValueAsString(new com.stock.system.agent.dto.ResearchReply(
                    List.of(new ResearchSummary.Item("这是根据当前授权资料整理的回答。", refs)),
                    List.of(questionMode.equals("financial") ? "财务数字不能直接证明订单变化，未读取订单资料。"
                            : "未读取财报与公告，无法确认实际增长。"))));
        }).when(questionModel).call(any(), any());
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

    @Test void questionReadsToolsPairsMessagesSumsUsageAndRestoresWithOldSummary() {
        generate();
        var run = ask("成员甲的判断有什么依据？");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.answer().summary()).isNull();
        assertThat(run.answer().followUp().question()).contains("判断");
        assertThat(run.answer().references()).extracting(reference -> reference.opinionId()).contains(opinion);
        assertThat(agent.restore(owner, null).messages()).hasSize(2);
        assertThat(agent.restore(owner, null).messages().get(0).answer().followUp()).isNotNull();
        var usage = jdbc.queryForMap("select model_calls, tool_calls, input_tokens, output_tokens from agent_run where id = ?", run.runId());
        assertThat(((Number) usage.get("model_calls")).longValue()).isEqualTo(2);
        assertThat(((Number) usage.get("tool_calls")).longValue()).isEqualTo(1);
        assertThat(((Number) usage.get("input_tokens")).longValue()).isEqualTo(200);
        assertThat(((Number) usage.get("output_tokens")).longValue()).isEqualTo(160);
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id = ?", BigDecimal.class, run.runId())).isEqualByComparingTo("0.00168");
    }

    @Test void currentQuoteAndDailyEvidenceAreJavaSnapshotsInsteadOfOpinionReferences() {
        questionMode = "quote";
        var quote = ask("最新可用价是多少？");
        assertThat(quote.status()).isEqualTo("SUCCEEDED");
        assertThat(quote.answer().followUp().evidence().get(0).type()).isEqualTo("QUOTE");
        assertThat(quote.answer().followUp().evidence().get(0).quote().simulated()).isTrue();
        questionMode = "daily";
        var daily = ask("9月1日至4日的名义收盘价变化？");
        assertThat(daily.status()).isEqualTo("SUCCEEDED");
        assertThat(daily.answer().followUp().evidence().stream().filter(evidence -> evidence.type().equals("DAILY_PRICES"))).hasSize(1);
    }

    @Test void financialEvidencePersistsAndRestoresWithExactAmountsAndCurrentAccess() throws Exception {
        questionMode = "financial";
        var run = ask("读取 FY 2025 的营收与归母利润。");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        var financial = run.answer().followUp().evidence().get(0).financialFacts();
        assertThat(financial.symbol()).isEqualTo("00700.HK");
        assertThat(financial.facts().get(0).current().amount()).isEqualByComparingTo("751766");
        assertThat(financial.facts().get(0).current().unit()).isEqualTo("MILLION_YUAN");
        assertThat(agent.restore(owner, null).messages().get(0).answer().followUp().evidence().get(0).financialFacts()).isEqualTo(financial);
        mvc.perform(get(base() + "/runs/" + run.runId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer.followUp.evidence[0].financialFacts.facts[0].current.amount").value("751766"));
        mvc.perform(get(base() + "/runs/" + run.runId()).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
    }

    @Test void modelFailureDiagnosticsPersistWithoutLeakingProviderTextOrClearingUnknownCost() {
        org.mockito.Mockito.doAnswer(invocation -> {
            var execution = ResearchModelExecution.current();
            execution.dispatch(); execution.usage("fixture", 10, 10);
            execution.dispatch();
            throw ResearchModelFailure.translate(new org.springframework.web.client.ResourceAccessException("private-raw-response",
                    new java.net.SocketTimeoutException("private-token")), "QUESTION_MODEL");
        }).when(questionModel).call(any(), any());
        var run = ask("请核对财务资料");
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("RESEARCH_AGENT_TIMEOUT");
        String stored = jdbc.queryForObject("select model_failure_json from agent_run where id = ?", String.class, run.runId());
        assertThat(stored).contains("TIMEOUT", "QUESTION_MODEL", "modelCall", "2", run.requestId())
                .doesNotContain("private-token", "private-raw-response");
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id = ?", java.math.BigDecimal.class, run.runId())).isNull();
    }

    @Test void unrecognizedFinancialReportVersionInvalidatesTheWholeStoredAnswer() {
        questionMode = "financial";
        var run = ask("读取 FY 2025 财务资料。");
        var stored = json.readTree(jdbc.queryForObject("select result_json from agent_message where run_id = ?", String.class, run.runId()));
        ((tools.jackson.databind.node.ObjectNode) stored.path("followUp").path("evidence").get(0).path("financialFacts")
                .path("reports").get(0)).put("sha256", "0".repeat(64));
        jdbc.update("update agent_message set result_json = ? where run_id = ?", json.writeValueAsString(stored), run.runId());
        var restored = agent.restore(owner, null).messages().get(0);
        assertThat(restored.status()).isEqualTo("INVALIDATED");
        assertThat(restored.answer()).isNull();
        assertThat(restored.errorCode()).isEqualTo("RESEARCH_AGENT_SOURCES_CHANGED");
    }

    @Test void forbiddenToolArgumentsGetPairedErrorsWithoutReadingOtherObjects() {
        questionMode = "invalid-args";
        var run = ask("读取其他空间的资料？");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.answer().references()).isEmpty();
        assertThat(jdbc.queryForObject("select tool_trace_json from agent_run where id = ?", String.class, run.runId())).contains("ERROR");
        assertThat(questionCalls).hasValue(2);
    }

    @Test void modelLoopStopsAtFourCallsAndThreeToolsWithoutAHiddenFifthRequest() {
        questionMode = "loop";
        var run = ask("一直查询行情直到找到答案？");
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("RESEARCH_AGENT_EXECUTION_LIMIT");
        assertThat(questionCalls).hasValue(ResearchQuestionService.MAX_MODEL_CALLS);
        assertThat(jdbc.queryForObject("select tool_calls from agent_run where id = ?", Integer.class, run.runId())).isEqualTo(ResearchQuestionService.MAX_TOOL_ATTEMPTS);
    }

    @Test void unknownSecondModelRequestCannotBeSettledUsingOnlyFirstRequestsTokens() {
        generate(); questionMode = "second-failure";
        var run = ask("最新价格？");
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id = ?", BigDecimal.class, run.runId())).isNull();
        assertThat(jdbc.queryForObject("select reserved_yuan from agent_run where id = ?", BigDecimal.class, run.runId())).isEqualByComparingTo(ResearchAgentRecordMapper.QUESTION_RESERVE);
        assertThat(agent.restore(owner, null).messages()).hasSize(1);
    }

    @Test void withdrawnHistoryCannotBeCarriedIntoNextQuestion() {
        generate();
        jdbc.update("update stock_opinion set withdrawn_at=current_timestamp(3), withdrawn_by_user_id=author_user_id where id=?", opinion);
        questionMode = "quote";
        var run = ask("最新报价？");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.answer().followUp().historyOmitted()).isTrue();
        assertThat(run.answer().references()).isEmpty();
    }

    @Test void requestLookupRecoversSuccessAndFailureWithoutAnotherModelCall() throws Exception {
        var completed = generate();
        mvc.perform(get(base() + "/requests/" + completed.requestId()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.runId").value(completed.runId()))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        mode.set("invalid");
        var failed = generate();
        assertThat(agent.requestStatus(owner, failed.requestId()).status()).isEqualTo("FAILED");
        assertThat(agent.requestStatus(owner, failed.requestId()).answer()).isNull();
        assertThat(calls).hasValue(2);
        mvc.perform(get(base() + "/requests/" + completed.requestId()).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
        mvc.perform(get(base() + "/requests/" + UUID.randomUUID()).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mvc.perform(get(base() + "/requests/bad").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base() + "/requests/" + completed.requestId())).andExpect(status().isUnauthorized());
        assertThat(calls).hasValue(2);
    }

    @Test void withdrawalBetweenToolRoundsStopsBeforeAnotherModelCall() {
        afterFirstQuestionResponse = () -> jdbc.update("update stock_opinion set withdrawn_at=current_timestamp(3),"
                + " withdrawn_by_user_id=author_user_id where id=?", opinion);
        var run = ask("成员观点的依据？");
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("RESEARCH_AGENT_SOURCES_CHANGED");
        assertThat(run.answer()).isNull();
        assertThat(questionCalls).hasValue(1);
        assertThat(agent.requestStatus(owner, run.requestId()).answer()).isNull();
    }

    @Test void losingAccessBetweenToolRoundsStopsWithoutPersistingAnAnswer() {
        afterFirstQuestionResponse = () -> jdbc.update("update workspace_member set status='LEFT',"
                + " last_inactive_at=current_timestamp(3) where workspace_id=? and user_id=?", owner.workspace(), owner.user());
        var run = ask("现在最新可用价？");
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.errorCode()).isEqualTo("WORKSPACE_MEMBERSHIP_INACTIVE");
        assertThat(run.answer()).isNull();
        assertThat(questionCalls).hasValue(1);
        assertThat(jdbc.queryForObject("select count(*) from agent_message where session_id in"
                + " (select id from agent_session where workspace_id=?)", Long.class, owner.workspace())).isZero();
        assertThat(jdbc.queryForObject("select status from agent_run where user_id=?", String.class, owner.user())).isEqualTo("FAILED");
        assertThatThrownBy(() -> agent.restore(owner, null)).isInstanceOf(BusinessException.class);
    }

    @Test void clearingDuringQuestionStopsFurtherToolsAndKeepsOnlyTheCallLedger() {
        afterFirstQuestionResponse = () -> agent.clear(owner);
        var run = ask("查当前观点");
        assertThat(run.status()).isEqualTo("CLEARED");
        assertThat(run.answer()).isNull();
        assertThat(questionCalls).hasValue(1);
        assertThat(agent.restore(owner, null).messages()).isEmpty();
        assertThat(jdbc.queryForObject("select model_calls from agent_run where id=?", Integer.class, run.runId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id=?", BigDecimal.class, run.runId()))
                .isEqualByComparingTo("0.00084");
    }

    @Test void historyKeepsAtMostSixWholeValidPairsAndTheCurrentQuestionIsLast() {
        for (int i = 0; i < 7; i++) generate();
        var run = ask("这是当前问题");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.answer().followUp().historyOmitted()).isTrue();
        var firstInput = questionInputs.get(0);
        assertThat(firstInput.stream().filter(AssistantMessage.class::isInstance)).hasSize(6);
        assertThat(firstInput.get(firstInput.size() - 1).getText()).isEqualTo("这是当前问题");
        assertThat(json.writeValueAsBytes(firstInput).length).isLessThan(60 * 1024);
    }

    @Test void changingTheDateFilterDoesNotCarryAnotherRangesHistoryIntoTheModel() {
        generate();
        var today = java.time.LocalDate.now();
        agent.summarize(owner, UUID.randomUUID().toString(), new ResearchSourceFilter(null, today, today));
        var run = ask("按完整周期回答");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.answer().followUp().historyOmitted()).isTrue();
        assertThat(questionInputs.get(0).stream().filter(AssistantMessage.class::isInstance)).hasSize(1);
    }

    @Test void cancellationWaitsForUsageThenStopsWithoutSavingAndIsIdempotent() throws Exception {
        mode.set("blocked");
        executor = Executors.newSingleThreadExecutor();
        String id = UUID.randomUUID().toString();
        var running = executor.submit(() -> agent.summarize(owner, id, ResearchSourceFilter.fullCycle()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var acknowledged = agent.cancel(owner, id);
        assertThat(acknowledged.status()).isEqualTo("RUNNING");
        assertThat(acknowledged.cancelRequested()).isTrue();
        assertThat(acknowledged.answer()).isNull();
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id=?", BigDecimal.class, acknowledged.runId())).isNull();
        release.countDown();
        var stopped = running.get(6, TimeUnit.SECONDS);
        assertThat(stopped.status()).isEqualTo("CANCELLED");
        assertThat(stopped.answer()).isNull();
        assertThat(agent.restore(owner, null).messages()).isEmpty();
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id=?", BigDecimal.class, stopped.runId()))
                .isEqualByComparingTo("0.0006");
        assertThat(agent.cancel(owner, id).status()).isEqualTo("CANCELLED");
        assertThat(agent.summarize(owner, id, ResearchSourceFilter.fullCycle()).status()).isEqualTo("CANCELLED");
        assertThat(calls).hasValue(1);
    }

    @Test void cancellationWithUnknownUsageRetainsReservation() throws Exception {
        mode.set("blocked");
        executor = Executors.newSingleThreadExecutor();
        String id = UUID.randomUUID().toString();
        var running = executor.submit(() -> agent.summarize(owner, id, ResearchSourceFilter.fullCycle()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        agent.cancel(owner, id);
        mode.set("unknown"); release.countDown();
        var stopped = running.get(6, TimeUnit.SECONDS);
        assertThat(stopped.status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id=?", BigDecimal.class, stopped.runId())).isNull();
        assertThat(jdbc.queryForObject("select reserved_yuan from agent_run where id=?", BigDecimal.class, stopped.runId()))
                .isEqualByComparingTo(ResearchAgentRecordMapper.RESERVE);
    }

    @Test void cancellationBetweenModelAndToolsPreventsAllFurtherCalls() {
        afterFirstQuestionResponse = () -> agent.cancel(owner, ResearchModelExecution.current().requestId());
        var run = ask("成员怎么看？");
        assertThat(run.status()).isEqualTo("CANCELLED");
        assertThat(run.answer()).isNull();
        assertThat(questionCalls).hasValue(1);
        assertThat(jdbc.queryForObject("select tool_calls from agent_run where id=?", Integer.class, run.runId())).isZero();
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id=?", BigDecimal.class, run.runId()))
                .isEqualByComparingTo("0.00084");
    }

    @Test void stoppingBeforeRunBindingAvoidsModelAndReleasesWholeReservation() {
        var progress = new ResearchAgentProgress() { @Override public boolean cancellationRequested() { return true; } };
        var run = agent.summarize(owner, UUID.randomUUID().toString(), ResearchSourceFilter.fullCycle(), progress);
        assertThat(run.status()).isEqualTo("CANCELLED");
        assertThat(run.cancelRequested()).isTrue();
        assertThat(calls).hasValue(0);
        assertThat(jdbc.queryForObject("select cost_upper_yuan from agent_run where id=?", BigDecimal.class, run.runId())).isZero();
    }

    @Test void cancellationCannotChangeOtherUsersOrCompletedRuns() {
        var completed = generate();
        assertThatThrownBy(() -> agent.cancel(member, completed.requestId())).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_RUN_NOT_FOUND));
        assertThatThrownBy(() -> agent.cancel(new Target(owner.user(), owner.workspace(), owner.stock(), owner.cycle() + 10000),
                completed.requestId())).isInstanceOf(BusinessException.class);
        assertThat(agent.cancel(owner, completed.requestId()).status()).isEqualTo("SUCCEEDED");
        assertThat(agent.restore(owner, null).messages()).hasSize(1);
    }

    @Test void sseRequiresAuthenticationAndSendsProgressBeforeValidatedFinalResult() throws Exception {
        String id = UUID.randomUUID().toString();
        String body = json.writeValueAsString(Map.of("requestId", id));
        mvc.perform(post(base() + "/summaries/stream").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mode.set("blocked");
        var stream = mvc.perform(post(base() + "/summaries/stream").header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").content(body)).andExpect(request().asyncStarted()).andReturn();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(stream.getResponse().getContentAsString()).contains("event:progress", "GENERATING").doesNotContain("event:result", "成员提出");
        mvc.perform(post(base() + "/requests/" + id + "/cancel").header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isNotFound());
        mvc.perform(post(base() + "/requests/" + id + "/cancel").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.run.cancelRequested").value(true));
        release.countDown();
        stream.getAsyncResult(6000);
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/event-stream"));
        assertThat(stream.getResponse().getContentAsString()).contains("event:result", "CANCELLED").doesNotContain("成员提出", "tool_trace", "arguments");
        assertThat(agent.restore(owner, null).messages()).isEmpty();
    }

    @Test void duplicateStreamSharesOneGenerationAndRejectsDifferentPayload() throws Exception {
        mode.set("blocked");
        String id = UUID.randomUUID().toString();
        String body = json.writeValueAsString(Map.of("requestId", id));
        var first = mvc.perform(post(base() + "/summaries/stream").header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").content(body)).andExpect(request().asyncStarted()).andReturn();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var second = mvc.perform(post(base() + "/summaries/stream").header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").content(body)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(post(base() + "/messages/stream").header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json").content(json.writeValueAsString(Map.of("requestId", id, "question", "另一问题"))))
                .andExpect(status().isConflict());
        release.countDown();
        first.getAsyncResult(6000); second.getAsyncResult(6000);
        mvc.perform(asyncDispatch(first)).andExpect(status().isOk());
        mvc.perform(asyncDispatch(second)).andExpect(status().isOk());
        assertThat(first.getResponse().getContentAsString()).contains("SUCCEEDED");
        assertThat(second.getResponse().getContentAsString()).contains("SUCCEEDED");
        assertThat(calls).hasValue(1);
    }

    @Test void concurrentRecoveryReadsDoNotDeadlockOrChangeSettledBudget() throws Exception {
        var completed = generate();
        BigDecimal occupied = jdbc.queryForObject("select occupied_yuan from agent_budget where id=1", BigDecimal.class);
        executor = Executors.newFixedThreadPool(4);
        var barrier = new CyclicBarrier(4);
        var readers = new ArrayList<Future<?>>();
        for (int reader = 0; reader < 4; reader++) readers.add(executor.submit(() -> {
            for (int attempt = 0; attempt < 12; attempt++) {
                barrier.await(5, TimeUnit.SECONDS);
                assertThat(agent.requestStatus(owner, completed.requestId()).status()).isEqualTo("SUCCEEDED");
            }
            return null;
        }));
        for (var reader : readers) reader.get(12, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("select occupied_yuan from agent_budget where id=1", BigDecimal.class)).isEqualByComparingTo(occupied);
        assertThat(calls).hasValue(1);
    }

    private com.stock.system.agent.vo.ResearchAgentResponseVO.Run ask(String question) {
        return agent.ask(owner, UUID.randomUUID().toString(), question, ResearchSourceFilter.fullCycle());
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
