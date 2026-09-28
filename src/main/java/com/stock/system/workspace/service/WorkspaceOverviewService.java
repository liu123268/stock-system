package com.stock.system.workspace.service;

import com.stock.system.workspace.vo.WorkspaceActivityResponseVO;
import com.stock.system.workspace.vo.WorkspaceOverviewResponseVO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
public class WorkspaceOverviewService {

    private static final int RECENT_LIMIT = 20;

    private final WorkspaceService workspaceService;
    private final JdbcTemplate jdbcTemplate;

    public WorkspaceOverviewService(WorkspaceService workspaceService, JdbcTemplate jdbcTemplate) {
        this.workspaceService = workspaceService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public WorkspaceOverviewResponseVO get(Long userId, Long workspaceId) {
        // 先完成空间成员校验，下面的批量 SQL 才能读取该空间的研究事实。
        workspaceService.detail(userId, workspaceId);
        Long activeCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM research_cycle rc
                JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
                WHERE ws.workspace_id = ? AND rc.status = 'ACTIVE'
                """, Long.class, workspaceId);
        List<WorkspaceActivityResponseVO> activities = jdbcTemplate.query(ACTIVITY_SQL,
                (result, row) -> toActivity(result),
                workspaceId, workspaceId, workspaceId, workspaceId, workspaceId, RECENT_LIMIT);
        return new WorkspaceOverviewResponseVO(activeCount == null ? 0 : activeCount, activities);
    }

    private WorkspaceActivityResponseVO toActivity(ResultSet result) throws SQLException {
        boolean withdrawn = result.getTimestamp("withdrawn_at") != null;
        var occurredAt = result.getTimestamp("occurred_at").toLocalDateTime();
        return new WorkspaceActivityResponseVO(
                result.getString("event_type"),
                result.getLong("workspace_stock_id"),
                result.getString("stock_code"),
                result.getString("stock_name"),
                result.getString("actor_nickname"),
                occurredAt,
                withdrawn ? null : result.getString("opinion_type"),
                withdrawn ? null : result.getString("stance"),
                result.getString("attention_level"),
                withdrawn
        );
    }

    private static final String ACTIVITY_SQL = """
            WITH events AS (
                SELECT rc.id event_id,
                       IF(rc.id = (SELECT MIN(first_cycle.id) FROM research_cycle first_cycle
                                   WHERE first_cycle.workspace_stock_id = rc.workspace_stock_id),
                          'STOCK_ADDED', 'FOLLOW_RESTARTED') event_type,
                       rc.workspace_stock_id, rc.started_by_user_id actor_user_id,
                       rc.started_at occurred_at, initial_op.id opinion_id,
                       CAST(NULL AS CHAR(20)) attention_level
                FROM research_cycle rc
                JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
                LEFT JOIN stock_opinion initial_op
                    ON initial_op.research_cycle_id = rc.id AND initial_op.type = 'INITIAL'
                WHERE ws.workspace_id = ?
                UNION ALL
                SELECT opinion.id, 'OPINION_POSTED', rc.workspace_stock_id,
                       opinion.author_user_id, opinion.created_at, opinion.id, NULL
                FROM stock_opinion opinion
                JOIN research_cycle rc ON rc.id = opinion.research_cycle_id
                JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
                WHERE ws.workspace_id = ? AND opinion.type IN ('UPDATE', 'REVIEW')
                UNION ALL
                SELECT opinion.id, 'OPINION_WITHDRAWN', rc.workspace_stock_id,
                       opinion.withdrawn_by_user_id, opinion.withdrawn_at, opinion.id, NULL
                FROM stock_opinion opinion
                JOIN research_cycle rc ON rc.id = opinion.research_cycle_id
                JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
                WHERE ws.workspace_id = ? AND opinion.type IN ('UPDATE', 'REVIEW')
                  AND opinion.withdrawn_at IS NOT NULL
                UNION ALL
                SELECT rc.id, IF(rc.status = 'CANCELLED', 'FOLLOW_CANCELLED', 'FOLLOW_ENDED'),
                       rc.workspace_stock_id,
                       IF(rc.status = 'CANCELLED', initial_op.withdrawn_by_user_id, NULL),
                       rc.ended_at, initial_op.id, NULL
                FROM research_cycle rc
                JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
                LEFT JOIN stock_opinion initial_op
                    ON initial_op.research_cycle_id = rc.id AND initial_op.type = 'INITIAL'
                WHERE ws.workspace_id = ? AND rc.status IN ('ENDED', 'CANCELLED')
                UNION ALL
                SELECT change_row.id, 'ATTENTION_CHANGED', rc.workspace_stock_id,
                       change_row.actor_user_id, change_row.changed_at, NULL,
                       change_row.to_level
                FROM research_attention_change change_row
                JOIN research_cycle rc ON rc.id = change_row.research_cycle_id
                JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
                WHERE ws.workspace_id = ?
            )
            SELECT events.event_type, events.workspace_stock_id,
                   stock.code stock_code, stock.name stock_name,
                   actor.nickname actor_nickname, events.occurred_at,
                   opinion.type opinion_type, opinion.stance,
                   opinion.withdrawn_at, events.attention_level
            FROM events
            JOIN workspace_stock ws ON ws.id = events.workspace_stock_id
            JOIN stock ON stock.id = ws.stock_id
            LEFT JOIN app_user actor ON actor.id = events.actor_user_id
            LEFT JOIN stock_opinion opinion ON opinion.id = events.opinion_id
            ORDER BY events.occurred_at DESC, events.event_id DESC, events.event_type DESC
            LIMIT ?
            """;
}
