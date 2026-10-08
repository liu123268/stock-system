package com.stock.system.stock.service;

import com.stock.system.stock.market.StockStatusChecks;
import com.stock.system.stock.market.StockStatusInfo;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.dto.CreateStockOpinionRequestDTO;
import com.stock.system.stock.entity.ResearchCycle;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.entity.StockOpinion;
import com.stock.system.stock.mapper.EndedWorkspaceStockRow;
import com.stock.system.stock.mapper.ResearchCycleMapper;
import com.stock.system.stock.mapper.StockOpinionMapper;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.vo.EndedWorkspaceStockResponseVO;
import com.stock.system.stock.vo.ResearchAuthorResponseVO;
import com.stock.system.stock.vo.ResearchCycleDetailResponseVO;
import com.stock.system.stock.vo.ResearchCycleStateResponseVO;
import com.stock.system.stock.vo.ResearchTimelinePageResponseVO;
import com.stock.system.stock.vo.StockOpinionResponseVO;
import com.stock.system.stock.vo.WorkspaceStockResearchResponseVO;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StockResearchLifecycleService {

    private static final Duration WITHDRAW_WINDOW = Duration.ofMinutes(5);
    private static final int TIMELINE_PAGE_SIZE = 20;

    private final WorkspaceService workspaceService;
    private final WorkspaceStockMapper workspaceStockMapper;
    private final ResearchCycleMapper researchCycleMapper;
    private final StockOpinionMapper stockOpinionMapper;
    private final UserService userService;
    private final JdbcTemplate jdbcTemplate;

    public StockResearchLifecycleService(
            WorkspaceService workspaceService,
            WorkspaceStockMapper workspaceStockMapper,
            ResearchCycleMapper researchCycleMapper,
            StockOpinionMapper stockOpinionMapper,
            UserService userService,
            JdbcTemplate jdbcTemplate
    ) {
        this.workspaceService = workspaceService;
        this.workspaceStockMapper = workspaceStockMapper;
        this.researchCycleMapper = researchCycleMapper;
        this.stockOpinionMapper = stockOpinionMapper;
        this.userService = userService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EndedWorkspaceStockResponseVO> listEnded(Long userId, Long workspaceId) {
        workspaceService.detail(userId, workspaceId);
        return workspaceStockMapper.selectEndedList(workspaceId).stream()
                .map(this::toEndedResponse)
                .toList();
    }

    public WorkspaceStockResearchResponseVO detail(
            Long userId,
            Long workspaceId,
            Long workspaceStockId
    ) {
        workspaceService.detail(userId, workspaceId);
        Stock stock = requireStock(workspaceId, workspaceStockId);
        ResearchCycle current = researchCycleMapper.selectActiveForStock(workspaceId, workspaceStockId);
        List<ResearchAuthorResponseVO> authors = jdbcTemplate.query("""
                SELECT DISTINCT author.id, author.nickname
                FROM stock_opinion opinion
                JOIN research_cycle rc ON rc.id = opinion.research_cycle_id
                JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
                JOIN app_user author ON author.id = opinion.author_user_id
                WHERE ws.workspace_id = ? AND ws.id = ?
                ORDER BY author.nickname, author.id
                """, (row, index) -> new ResearchAuthorResponseVO(
                row.getLong("id"), row.getString("nickname")), workspaceId, workspaceStockId);
        return new WorkspaceStockResearchResponseVO(
                workspaceStockId,
                stock.getCode(),
                stock.getName(),
                stock.getExchange(),
                stock.getCurrency(),
                StockStatusChecks.currentStatus(stock),
                current == null ? null : toCycleSummary(current),
                authors,
                StockStatusInfo.from(stock)
        );
    }

    public ResearchTimelinePageResponseVO timeline(
            Long userId, Long workspaceId, Long workspaceStockId,
            String author, String stance, String type, String cursor
    ) {
        var workspace = workspaceService.detail(userId, workspaceId);
        requireStock(workspaceId, workspaceStockId);
        StringBuilder sql = new StringBuilder(TIMELINE_SQL);
        List<Object> parameters = new ArrayList<>(List.of(workspaceId, workspaceStockId));
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = cursor.split(":", -1);
            try {
                if (parts.length != 2) throw new NumberFormatException();
                long cycleId = Long.parseLong(parts[0]);
                long opinionId = Long.parseLong(parts[1]);
                if (cycleId <= 0 || opinionId <= 0) throw new NumberFormatException();
                sql.append(" AND (rc.id < ? OR (rc.id = ? AND opinion.id < ?))");
                parameters.add(cycleId);
                parameters.add(cycleId);
                parameters.add(opinionId);
            } catch (NumberFormatException invalidCursor) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
        if (author != null && !author.isBlank()) {
            try {
                long authorId = Long.parseLong(author);
                if (authorId <= 0) throw new NumberFormatException();
                sql.append(" AND opinion.author_user_id = ?");
                parameters.add(authorId);
            } catch (NumberFormatException invalidAuthor) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
        }
        if (stance != null && !stance.isBlank()) {
            if (!List.of("BULLISH", "NEUTRAL", "BEARISH").contains(stance)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            sql.append(" AND opinion.stance = ?");
            parameters.add(stance);
        }
        if (type != null && !type.isBlank()) {
            if (!List.of("INITIAL", "UPDATE", "REVIEW").contains(type)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST);
            }
            sql.append(" AND opinion.type = ?");
            parameters.add(type);
        }
        sql.append(" ORDER BY rc.id DESC, opinion.id DESC LIMIT ?");
        parameters.add(TIMELINE_PAGE_SIZE + 1);
        boolean workspaceActive = "ACTIVE".equals(workspace.status());
        List<TimelineRow> rows = jdbcTemplate.query(sql.toString(),
                (result, index) -> toTimelineRow(result, userId, workspaceActive),
                parameters.toArray());
        boolean hasMore = rows.size() > TIMELINE_PAGE_SIZE;
        List<TimelineRow> pageRows = rows.subList(0, Math.min(TIMELINE_PAGE_SIZE, rows.size()));
        Map<Long, ResearchCycleDetailResponseVO> cycles = new LinkedHashMap<>();
        for (TimelineRow row : pageRows) {
            var cycle = cycles.computeIfAbsent(row.cycle().id(), ignored -> withOpinions(row.cycle()));
            cycle.opinions().add(row.opinion());
        }
        TimelineRow last = pageRows.isEmpty() ? null : pageRows.get(pageRows.size() - 1);
        String nextCursor = hasMore ? last.cycle().id() + ":" + last.opinion().id() : null;
        return new ResearchTimelinePageResponseVO(List.copyOf(cycles.values()), nextCursor);
    }

    private ResearchCycleDetailResponseVO withOpinions(ResearchCycleDetailResponseVO cycle) {
        return new ResearchCycleDetailResponseVO(
                cycle.id(), cycle.status(), cycle.attentionLevel(), cycle.startedByUserId(),
                cycle.startedByNickname(), cycle.startedAt(), cycle.startPrice(), cycle.quoteTime(),
                cycle.quoteSource(), cycle.simulated(), cycle.endedAt(), new ArrayList<>());
    }

    private TimelineRow toTimelineRow(ResultSet row, Long userId, boolean workspaceActive)
            throws SQLException {
        var cycle = new ResearchCycleDetailResponseVO(
                row.getLong("cycle_id"), row.getString("cycle_status"),
                row.getString("attention_level"), row.getLong("started_by_user_id"),
                row.getString("starter_nickname"), row.getTimestamp("started_at").toLocalDateTime(),
                row.getBigDecimal("start_price"), row.getTimestamp("quote_time").toLocalDateTime(),
                row.getString("quote_source"), row.getBoolean("is_simulated"),
                row.getTimestamp("ended_at") == null ? null : row.getTimestamp("ended_at").toLocalDateTime(),
                List.of());
        boolean withdrawn = row.getTimestamp("withdrawn_at") != null;
        LocalDateTime createdAt = row.getTimestamp("created_at").toLocalDateTime();
        Long authorId = row.getLong("author_user_id");
        var opinion = new StockOpinionResponseVO(
                row.getLong("opinion_id"), authorId, row.getString("author_nickname"),
                row.getString("type"), row.getString("stance"),
                withdrawn ? null : row.getString("reason"),
                withdrawn ? null : row.getString("observation_note"),
                row.getString("horizon"),
                withdrawn ? null : row.getBigDecimal("target_price"),
                withdrawn ? null : row.getBigDecimal("stop_loss_price"),
                createdAt, withdrawn,
                withdrawn ? row.getTimestamp("withdrawn_at").toLocalDateTime() : null,
                canWithdraw(createdAt, authorId, userId,
                        workspaceActive && "ACTIVE".equals(cycle.status()), withdrawn));
        return new TimelineRow(cycle, opinion);
    }

    private record TimelineRow(ResearchCycleDetailResponseVO cycle, StockOpinionResponseVO opinion) {}

    private static final String TIMELINE_SQL = """
            SELECT rc.id cycle_id, rc.status cycle_status, rc.attention_level,
                   rc.started_by_user_id, starter.nickname starter_nickname,
                   rc.started_at, rc.start_price, rc.quote_time, rc.quote_source,
                   rc.is_simulated, rc.ended_at,
                   opinion.id opinion_id, opinion.author_user_id, author.nickname author_nickname,
                   opinion.type, opinion.stance, opinion.reason, opinion.observation_note,
                   opinion.horizon, opinion.target_price, opinion.stop_loss_price,
                   opinion.created_at, opinion.withdrawn_at
            FROM research_cycle rc
            JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
            JOIN stock_opinion opinion ON opinion.research_cycle_id = rc.id
            JOIN app_user starter ON starter.id = rc.started_by_user_id
            JOIN app_user author ON author.id = opinion.author_user_id
            WHERE ws.workspace_id = ? AND ws.id = ?
            """;

    @Transactional
    public ResearchCycleStateResponseVO end(
            Long userId,
            Long workspaceId,
            Long workspaceStockId,
            Long researchCycleId
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);
        requireStock(workspaceId, workspaceStockId);
        ResearchCycle cycle = requireActiveCycle(workspaceId, workspaceStockId, researchCycleId);
        LocalDateTime endedAt = LocalDateTime.now();
        cycle.setStatus("ENDED");
        cycle.setEndedAt(endedAt);
        researchCycleMapper.updateById(cycle);
        return new ResearchCycleStateResponseVO(
                workspaceStockId,
                cycle.getId(),
                cycle.getStatus(),
                endedAt
        );
    }

    @Transactional
    public StockOpinionResponseVO createOpinion(
            Long userId,
            Long workspaceId,
            Long workspaceStockId,
            CreateStockOpinionRequestDTO request
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);
        requireStock(workspaceId, workspaceStockId);
        ResearchCycle cycle = requireActiveCycle(
                workspaceId, workspaceStockId, request.researchCycleId());

        StockOpinion opinion = new StockOpinion();
        opinion.setResearchCycleId(cycle.getId());
        opinion.setAuthorUserId(userId);
        opinion.setType(request.type());
        opinion.setStance(request.stance());
        opinion.setReason(request.reason().trim());
        opinion.setObservationNote(normalizeOptionalText(request.observationNote()));
        opinion.setHorizon(request.horizon());
        opinion.setTargetPrice(request.targetPrice());
        opinion.setStopLossPrice(request.stopLossPrice());
        opinion.setCreatedAt(LocalDateTime.now());
        stockOpinionMapper.insert(opinion);

        AppUser author = userService.findById(userId);
        return toOpinionResponse(opinion, author.getNickname(), userId, true);
    }

    @Transactional
    public void withdrawOpinion(
            Long userId,
            Long workspaceId,
            Long workspaceStockId,
            Long opinionId
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);
        StockOpinion opinion = stockOpinionMapper.selectForUpdate(
                workspaceId,
                workspaceStockId,
                opinionId
        );
        if (opinion == null) {
            throw new BusinessException(ErrorCode.STOCK_OPINION_NOT_FOUND);
        }
        ResearchCycle cycle = researchCycleMapper.selectById(opinion.getResearchCycleId());
        if (!"ACTIVE".equals(cycle.getStatus())) {
            throw new BusinessException(ErrorCode.ACTIVE_RESEARCH_CYCLE_NOT_FOUND);
        }
        if (!opinion.getAuthorUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.STOCK_OPINION_ACCESS_DENIED);
        }
        if (opinion.getWithdrawnAt() != null) {
            throw new BusinessException(ErrorCode.STOCK_OPINION_ALREADY_WITHDRAWN);
        }
        LocalDateTime withdrawnAt = LocalDateTime.now();
        if (opinion.getCreatedAt().plus(WITHDRAW_WINDOW).isBefore(withdrawnAt)) {
            throw new BusinessException(ErrorCode.STOCK_OPINION_WITHDRAW_WINDOW_EXPIRED);
        }
        opinion.setWithdrawnAt(withdrawnAt);
        opinion.setWithdrawnByUserId(userId);
        stockOpinionMapper.updateById(opinion);
        if ("INITIAL".equals(opinion.getType())) {
            cycle.setStatus("CANCELLED");
            cycle.setEndedAt(withdrawnAt);
            researchCycleMapper.updateById(cycle);
        }
    }

    private Stock requireStock(Long workspaceId, Long workspaceStockId) {
        Stock stock = workspaceStockMapper.selectStockIdentity(workspaceId, workspaceStockId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        }
        return stock;
    }

    private ResearchCycle requireActiveCycle(
            Long workspaceId,
            Long workspaceStockId,
            Long expectedResearchCycleId
    ) {
        ResearchCycle cycle = researchCycleMapper.selectActiveForUpdate(
                workspaceId,
                workspaceStockId
        );
        if (cycle == null || !cycle.getId().equals(expectedResearchCycleId)) {
            throw new BusinessException(ErrorCode.RESEARCH_CYCLE_CHANGED);
        }
        return cycle;
    }

    private ResearchCycleDetailResponseVO toCycleSummary(ResearchCycle cycle) {
        AppUser starter = userService.findById(cycle.getStartedByUserId());
        return new ResearchCycleDetailResponseVO(
                cycle.getId(),
                cycle.getStatus(),
                cycle.getAttentionLevel(),
                cycle.getStartedByUserId(),
                starter.getNickname(),
                cycle.getStartedAt(),
                cycle.getStartPrice(),
                cycle.getQuoteTime(),
                cycle.getQuoteSource(),
                Boolean.TRUE.equals(cycle.getIsSimulated()),
                cycle.getEndedAt(),
                List.of()
        );
    }

    private StockOpinionResponseVO toOpinionResponse(
            StockOpinion opinion,
            String authorNickname,
            Long userId,
            boolean active
    ) {
        boolean withdrawn = opinion.getWithdrawnAt() != null;
        return new StockOpinionResponseVO(
                opinion.getId(),
                opinion.getAuthorUserId(),
                authorNickname,
                opinion.getType(),
                opinion.getStance(),
                withdrawn ? null : opinion.getReason(),
                withdrawn ? null : opinion.getObservationNote(),
                opinion.getHorizon(),
                withdrawn ? null : opinion.getTargetPrice(),
                withdrawn ? null : opinion.getStopLossPrice(),
                opinion.getCreatedAt(),
                withdrawn,
                opinion.getWithdrawnAt(),
                canWithdraw(opinion.getCreatedAt(), opinion.getAuthorUserId(), userId, active, withdrawn)
        );
    }

    private boolean canWithdraw(
            LocalDateTime createdAt,
            Long authorUserId,
            Long userId,
            boolean active,
            boolean withdrawn
    ) {
        return active
                && !withdrawn
                && authorUserId.equals(userId)
                && !createdAt.plus(WITHDRAW_WINDOW).isBefore(LocalDateTime.now());
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private EndedWorkspaceStockResponseVO toEndedResponse(EndedWorkspaceStockRow row) {
        return new EndedWorkspaceStockResponseVO(
                row.getWorkspaceStockId(),
                row.getCode(),
                row.getName(),
                row.getExchange(),
                row.getCurrency(),
                row.getResearchCycleId(),
                row.getStatus(),
                row.getStartedAt(),
                row.getEndedAt()
        );
    }
}
