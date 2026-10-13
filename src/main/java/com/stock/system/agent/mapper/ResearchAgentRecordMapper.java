package com.stock.system.agent.mapper;

import com.stock.system.agent.service.ResearchModelExecution;
import com.stock.system.agent.service.ResearchSummaryPrompt;
import com.stock.system.agent.vo.ResearchAgentResponseVO.Answer;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

/** 短事务管理个人记录和独立费用账本；数据库锁内禁止调用外部模型。 */
@Repository
public class ResearchAgentRecordMapper {
    // 2026-10-13 官方 Flash 高峰价输入 2／输出 8 元每百万；输入按更保守的 2M 上限预留。
    public static final BigDecimal RESERVE = new BigDecimal("4.01600000");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final BigDecimal budget;

    public ResearchAgentRecordMapper(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper json,
                                    @Value("${stock-system.agent.budget-yuan:0}") BigDecimal budget) {
        this.jdbc = jdbc;
        this.json = json;
        this.budget = budget;
        this.tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(10);
    }

    public boolean funded() { return budget.signum() > 0; }

    public SessionRow session(Target target) {
        return jdbc.query("select id, context_version from agent_session where user_id = ? and workspace_id = ?"
                        + " and workspace_stock_id = ? and research_cycle_id = ?",
                (rs, n) -> new SessionRow(rs.getLong(1), rs.getLong(2)), target.user(), target.workspace(),
                target.stock(), target.cycle()).stream().findFirst().orElse(null);
    }

    public RunRow request(long user, String requestId) {
        return rows("where r.user_id = ? and r.request_id = ?", user, requestId).stream().findFirst().orElse(null);
    }

    public RunRow run(Target target, long id) {
        return rows("where r.id = ? and s.user_id = ? and s.workspace_id = ? and s.workspace_stock_id = ?"
                        + " and s.research_cycle_id = ?", id, target.user(), target.workspace(), target.stock(),
                target.cycle()).stream().findFirst().orElseThrow(() ->
                new BusinessException(ErrorCode.RESEARCH_AGENT_RUN_NOT_FOUND));
    }

    public List<RunRow> messages(long sessionId, long version, Long beforeRunId) {
        return rows("where r.session_id = ? and r.context_version = ? and m.id is not null and r.id < ?"
                + " order by r.id desc limit 7", sessionId, version, beforeRunId == null ? Long.MAX_VALUE : beforeRunId);
    }

    public RunRow active(long sessionId, long version) {
        return rows("where r.session_id = ? and r.context_version = ? and r.status = 'RUNNING' order by r.id desc limit 1",
                sessionId, version).stream().findFirst().orElse(null);
    }

    /** 断线／重启恢复只收敛超时运行，不重发模型请求，也不释放用量未知的费用占用。 */
    public void reconcile(Target target) {
        tx.executeWithoutResult(unused -> {
            lockBudget();
            jdbc.update("update agent_run r join agent_session s on s.id = r.session_id"
                    + " set r.status = 'INTERRUPTED', r.error_code = 'RESEARCH_AGENT_TIMEOUT', r.finished_at = current_timestamp(3)"
                    + " where s.user_id = ? and s.workspace_id = ? and s.workspace_stock_id = ? and s.research_cycle_id = ?"
                    + " and r.status = 'RUNNING' and r.started_at < current_timestamp(3) - interval 120 second",
                    target.user(), target.workspace(), target.stock(), target.cycle());
        });
    }

    public Start begin(Target target, String requestId, String hash, boolean needsModel) {
        return tx.execute(unused -> {
            lockBudget();
            // 已超过网络上限且未正常结束的旧运行只收敛状态，用量未知的占用继续保留。
            jdbc.update("update agent_run set status = 'INTERRUPTED', error_code = 'RESEARCH_AGENT_TIMEOUT',"
                    + " finished_at = current_timestamp(3) where status = 'RUNNING'"
                    + " and started_at < current_timestamp(3) - interval 120 second");
            RunRow duplicate = request(target.user(), requestId);
            if (duplicate != null) return new Start(duplicate, false);
            if (jdbc.queryForObject("select count(*) from agent_run where status = 'RUNNING' and user_id = ?",
                    Long.class, target.user()) > 0 || jdbc.queryForObject(
                    "select count(*) from agent_run where status = 'RUNNING'", Long.class) >= 2) {
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_BUSY);
            }
            BigDecimal reserved = needsModel ? RESERVE : BigDecimal.ZERO;
            BigDecimal occupied = jdbc.queryForObject("select occupied_yuan from agent_budget where id = 1",
                    BigDecimal.class);
            if (needsModel && occupied.add(reserved).compareTo(budget) > 0)
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_BUDGET_EXCEEDED);
            jdbc.update("insert ignore into agent_session(user_id, workspace_id, workspace_stock_id, research_cycle_id)"
                            + " values (?, ?, ?, ?)", target.user(), target.workspace(), target.stock(), target.cycle());
            SessionRow session = session(target);
            long version = jdbc.queryForObject("select context_version from agent_session where id = ? for update",
                    Long.class, session.id());
            long id = insertId("insert into agent_run(session_id, user_id, request_id, request_hash, context_version,"
                            + " status, prompt_version, reserved_yuan) values (?, ?, ?, ?, ?, 'RUNNING', ?, ?)",
                    session.id(), target.user(), requestId, hash, version, ResearchSummaryPrompt.VERSION, reserved);
            jdbc.update("update agent_budget set occupied_yuan = occupied_yuan + ? where id = 1", reserved);
            return new Start(run(target, id), true);
        });
    }

    /** 清空和完成均先锁预算再锁会话，保持一致锁顺序；供应商用量即使回答无效也结算。 */
    public void finish(Target target, RunRow run, ResearchModelExecution execution,
                       ResearchCycleSourcesResponseVO dependencies, Answer answer, ErrorCode error,
                       Supplier<?> finalAccessCheck) {
        tx.executeWithoutResult(unused -> {
            lockBudget();
            RunRow current = run(target, run.id());
            if (!current.status().equals("RUNNING")) return;
            long version = jdbc.queryForObject("select context_version from agent_session where id = ? for update",
                    Long.class, run.sessionId());
            String status = "FAILED";
            ErrorCode finalError = error;
            if (version != run.version()) { status = "CLEARED"; finalError = ErrorCode.RESEARCH_AGENT_CLEARED; }
            else if (answer != null && error == null) {
                try {
                    finalAccessCheck.get();
                    execution.remainingMillis();
                    status = "SUCCEEDED";
                } catch (BusinessException failure) { finalError = failure.getErrorCode(); }
            }
            BigDecimal cost = settle(execution, run.reserved());
            jdbc.update("update agent_run set status = ?, error_code = ?, finished_at = current_timestamp(3),"
                            + " dispatched = ?, model = ?, input_tokens = ?, output_tokens = ?, cost_upper_yuan = ? where id = ?",
                    status, finalError == null ? null : finalError.name(), execution.dispatched,
                    execution.model == null ? null : execution.model.substring(0, Math.min(100, execution.model.length())),
                    safeTokens(execution.inputTokens), safeTokens(execution.outputTokens), cost, run.id());
            if (status.equals("SUCCEEDED")) {
                jdbc.update("insert into agent_message(session_id, run_id, result_json, dependencies_json) values (?, ?, ?, ?)",
                        run.sessionId(), run.id(), json.writeValueAsString(answer), json.writeValueAsString(dependencies));
            }
        });
    }

    public void clear(Target target, Supplier<?> accessCheck) {
        tx.executeWithoutResult(unused -> {
            lockBudget();
            accessCheck.get();
            SessionRow session = session(target);
            if (session == null) return;
            jdbc.queryForObject("select context_version from agent_session where id = ? for update", Long.class, session.id());
            jdbc.update("update agent_session set context_version = context_version + 1 where id = ?", session.id());
            jdbc.update("delete from agent_message where session_id = ?", session.id());
            jdbc.update("update agent_run set status = 'CLEARED', error_code = 'RESEARCH_AGENT_CLEARED'"
                    + " where session_id = ? and status <> 'RUNNING'", session.id());
        });
    }

    private BigDecimal settle(ResearchModelExecution execution, BigDecimal reserved) {
        BigDecimal cost;
        if (!execution.dispatched) cost = BigDecimal.ZERO;
        else if (execution.inputTokens == null || execution.outputTokens == null
                || execution.inputTokens < 0 || execution.outputTokens < 0) return null;
        else cost = BigDecimal.valueOf(execution.inputTokens).multiply(new BigDecimal("0.000002"))
                    .add(BigDecimal.valueOf(execution.outputTokens).multiply(new BigDecimal("0.000008")));
        // 按缓存未命中的高峰价计算上界，非供应商账单；异常超出预留继续占用并停止后续预算。
        jdbc.update("update agent_budget set occupied_yuan = occupied_yuan - ? + ? where id = 1", reserved, cost);
        return cost;
    }

    private Integer safeTokens(Integer tokens) { return tokens != null && tokens >= 0 ? tokens : null; }

    private void lockBudget() {
        jdbc.update("insert ignore into agent_budget(id, occupied_yuan) values (1, 0)");
        jdbc.queryForObject("select occupied_yuan from agent_budget where id = 1 for update", BigDecimal.class);
    }

    private List<RunRow> rows(String suffix, Object... args) {
        return jdbc.query("select r.*, s.context_version as current_version, m.result_json, m.dependencies_json"
                + " from agent_run r join agent_session s on s.id = r.session_id"
                + " left join agent_message m on m.run_id = r.id " + suffix, (rs, n) -> new RunRow(
                rs.getLong("id"), rs.getLong("session_id"), rs.getString("request_id"), rs.getString("request_hash"),
                rs.getLong("context_version"), rs.getLong("current_version"), rs.getString("status"),
                rs.getString("error_code"), rs.getTimestamp("started_at").toLocalDateTime(),
                date(rs.getTimestamp("finished_at")), rs.getBigDecimal("reserved_yuan"),
                rs.getString("result_json"), rs.getString("dependencies_json")), args);
    }

    private long insertId(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private LocalDateTime date(Timestamp at) { return at == null ? null : at.toLocalDateTime(); }
    public record Target(long user, long workspace, long stock, long cycle) {}
    public record SessionRow(long id, long version) {}
    public record Start(RunRow run, boolean created) {}
    public record RunRow(long id, long sessionId, String requestId, String hash, long version, long currentVersion,
                         String status, String errorCode, LocalDateTime startedAt, LocalDateTime finishedAt,
                         BigDecimal reserved, String answerJson, String dependenciesJson) {}
}
