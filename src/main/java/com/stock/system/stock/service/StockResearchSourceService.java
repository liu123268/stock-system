package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.ResearchCycle;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.ResearchCycleMapper;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO.Coverage;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO.OpinionSource;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO.Scope;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class StockResearchSourceService {

    private static final int MAX_OPINION_COUNT = 100;
    private static final int MAX_TEXT_CODE_POINTS = 20_000;

    private final WorkspaceService workspaceService;
    private final WorkspaceStockMapper workspaceStockMapper;
    private final ResearchCycleMapper researchCycleMapper;
    private final JdbcTemplate jdbcTemplate;

    public StockResearchSourceService(
            WorkspaceService workspaceService,
            WorkspaceStockMapper workspaceStockMapper,
            ResearchCycleMapper researchCycleMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.workspaceService = workspaceService;
        this.workspaceStockMapper = workspaceStockMapper;
        this.researchCycleMapper = researchCycleMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 统计和正文读取使用同一短读事务；后续模型网络调用必须在返回之后进行。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ResearchCycleSourcesResponseVO readCycle(
            Long userId, Long workspaceId, Long workspaceStockId, Long researchCycleId
    ) {
        BoundCycle bound = requireCycle(userId, workspaceId, workspaceStockId, researchCycleId);
        SourceSize size = jdbcTemplate.queryForObject("""
                select current_timestamp(6) read_at, count(*) valid_count,
                       coalesce(sum(char_length(opinion.reason)
                           + coalesce(char_length(opinion.observation_note), 0)), 0) text_count
                """ + SOURCE_FROM_SQL, (row, index) -> new SourceSize(
                row.getTimestamp("read_at").toLocalDateTime(),
                row.getLong("valid_count"), row.getLong("text_count")),
                workspaceId, workspaceStockId, researchCycleId);

        boolean exceedsLimit = size.count() > MAX_OPINION_COUNT
                || size.textCodePoints() > MAX_TEXT_CODE_POINTS;
        List<OpinionSource> opinions = exceedsLimit ? List.of() : jdbcTemplate.query(
                SOURCE_SELECT_SQL + SOURCE_FROM_SQL
                        + " order by opinion.created_at, opinion.id limit ?",
                (row, index) -> toSource(row),
                workspaceId, workspaceStockId, researchCycleId, MAX_OPINION_COUNT);
        Scope scope = new Scope(size.readAt(), size.count(), size.textCodePoints(), opinions.size(),
                exceedsLimit ? Coverage.LIMIT_EXCEEDED : Coverage.COMPLETE,
                MAX_OPINION_COUNT, MAX_TEXT_CODE_POINTS);
        ResearchCycle cycle = bound.cycle();
        return new ResearchCycleSourcesResponseVO(workspaceStockId, bound.stock().getCode(),
                bound.stock().getName(), cycle.getId(), cycle.getStatus(), cycle.getStartedAt(),
                cycle.getEndedAt(), scope, List.copyOf(opinions));
    }

    /** 每次查看原文都重新检查权限和撤回状态，不使用先前响应中的缓存正文。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OpinionSource readOpinion(
            Long userId, Long workspaceId, Long workspaceStockId, Long researchCycleId, Long opinionId
    ) {
        requirePositive(opinionId);
        requireCycle(userId, workspaceId, workspaceStockId, researchCycleId);
        List<OpinionSource> sources = jdbcTemplate.query(
                SOURCE_SELECT_SQL + SOURCE_FROM_SQL + " and opinion.id = ?",
                (row, index) -> toSource(row),
                workspaceId, workspaceStockId, researchCycleId, opinionId);
        if (sources.isEmpty()) {
            throw new BusinessException(ErrorCode.STOCK_OPINION_NOT_FOUND);
        }
        return sources.get(0);
    }

    /** 模型返回后重新授权并核对原快照的全部来源；新增观点只标记，不自动重跑。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceValidity revalidate(
            Long userId, Long workspaceId, Long workspaceStockId, Long researchCycleId,
            ResearchCycleSourcesResponseVO expected
    ) {
        requireCycle(userId, workspaceId, workspaceStockId, researchCycleId);
        if (expected == null || !Objects.equals(expected.workspaceStockId(), workspaceStockId)
                || !Objects.equals(expected.researchCycleId(), researchCycleId)
                || expected.scope().coverage() != Coverage.COMPLETE) {
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
        }
        // 当前范围可能因新增观点超限，只统计数量；正文仅查原快照最多 100 条。
        Long currentCount = jdbcTemplate.queryForObject("select count(*) " + SOURCE_FROM_SQL,
                Long.class, workspaceId, workspaceStockId, researchCycleId);
        Map<Long, OpinionSource> expectedById = new HashMap<>();
        for (OpinionSource source : expected.opinions()) {
            expectedById.put(source.id(), source);
        }
        if (!expectedById.isEmpty()) {
            String placeholders = String.join(",", expectedById.keySet().stream().map(unused -> "?").toList());
            List<Object> parameters = new java.util.ArrayList<>(List.of(workspaceId, workspaceStockId, researchCycleId));
            parameters.addAll(expectedById.keySet());
            List<OpinionSource> current = jdbcTemplate.query(SOURCE_SELECT_SQL + SOURCE_FROM_SQL
                            + " and opinion.id in (" + placeholders + ")",
                    (row, index) -> toSource(row), parameters.toArray());
            if (current.size() != expectedById.size()
                    || current.stream().anyMatch(source -> !source.equals(expectedById.get(source.id())))) {
                throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
            }
        }
        return new SourceValidity(currentCount > expectedById.size());
    }

    public record SourceValidity(boolean newOpinionsAvailable) {}

    private BoundCycle requireCycle(
            Long userId, Long workspaceId, Long workspaceStockId, Long researchCycleId
    ) {
        requirePositive(workspaceId);
        requirePositive(workspaceStockId);
        requirePositive(researchCycleId);
        workspaceService.detail(userId, workspaceId);
        Stock stock = workspaceStockMapper.selectStockIdentity(workspaceId, workspaceStockId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        }
        ResearchCycle cycle = researchCycleMapper.selectInWorkspaceStock(
                workspaceId, workspaceStockId, researchCycleId);
        if (cycle == null) {
            throw new BusinessException(ErrorCode.RESEARCH_CYCLE_NOT_FOUND);
        }
        return new BoundCycle(stock, cycle);
    }

    private void requirePositive(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private OpinionSource toSource(ResultSet row) throws SQLException {
        return new OpinionSource(row.getLong("id"), row.getLong("author_user_id"),
                row.getString("author_nickname"), row.getString("type"), row.getString("stance"),
                row.getString("reason"), row.getString("observation_note"), row.getString("horizon"),
                row.getBigDecimal("target_price"), row.getBigDecimal("stop_loss_price"),
                row.getTimestamp("created_at").toLocalDateTime());
    }

    private record BoundCycle(Stock stock, ResearchCycle cycle) {}
    private record SourceSize(LocalDateTime readAt, long count, long textCodePoints) {}

    private static final String SOURCE_SELECT_SQL = """
            select opinion.id, opinion.author_user_id, author.nickname author_nickname,
                   opinion.type, opinion.stance, opinion.reason, opinion.observation_note,
                   opinion.horizon, opinion.target_price, opinion.stop_loss_price, opinion.created_at
            """;

    private static final String SOURCE_FROM_SQL = """
            from stock_opinion opinion
            join research_cycle rc on rc.id = opinion.research_cycle_id
            join workspace_stock ws on ws.id = rc.workspace_stock_id
            join app_user author on author.id = opinion.author_user_id
            where ws.workspace_id = ? and ws.id = ? and rc.id = ?
              and opinion.withdrawn_at is null
            """;
}
