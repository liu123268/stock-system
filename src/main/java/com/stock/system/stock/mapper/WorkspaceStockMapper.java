package com.stock.system.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.stock.entity.WorkspaceStock;
import com.stock.system.stock.entity.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkspaceStockMapper extends BaseMapper<WorkspaceStock> {

    @Select("""
            SELECT s.*
            FROM workspace_stock ws
            JOIN stock s ON s.id = ws.stock_id
            WHERE ws.workspace_id = #{workspaceId}
              AND ws.id = #{workspaceStockId}
            """)
    Stock selectStockIdentity(Long workspaceId, Long workspaceStockId);

    @Select("""
            SELECT
                ws.id AS workspace_stock_id,
                rc.id AS active_research_cycle_id
            FROM workspace_stock ws
            LEFT JOIN research_cycle rc
                ON rc.workspace_stock_id = ws.id
                AND rc.status = 'ACTIVE'
            WHERE ws.workspace_id = #{workspaceId}
              AND ws.stock_id = #{stockId}
            """)
    WorkspaceStockTrackingRow selectTrackingState(Long workspaceId, Long stockId);

    @Select("""
            SELECT
                ws.id AS workspace_stock_id,
                rc.id AS research_cycle_id,
                s.code,
                s.name,
                s.exchange,
                s.currency,
                s.listing_status,
                s.trade_status, s.status_checked_at, s.status_attempted_at, s.source as stock_source,
                rc.attention_level,
                rc.started_by_user_id,
                starter.nickname AS started_by_nickname,
                rc.started_at,
                rc.start_price,
                rc.quote_time,
                rc.quote_source,
                rc.is_simulated AS simulated,
                latest_opinion.id AS latest_opinion_id,
                latest_opinion.type AS latest_opinion_type,
                latest_opinion.author_user_id AS latest_opinion_author_user_id,
                latest_author.nickname AS latest_opinion_author_nickname,
                latest_opinion.stance AS latest_opinion_stance,
                latest_opinion.created_at AS latest_opinion_created_at,
                opinion_stats.opinion_count,
                opinion_stats.participant_count
            FROM workspace_stock ws
            JOIN stock s ON s.id = ws.stock_id
            JOIN research_cycle rc
                ON rc.workspace_stock_id = ws.id
                AND rc.status = 'ACTIVE'
            JOIN app_user starter ON starter.id = rc.started_by_user_id
            JOIN stock_opinion latest_opinion
                ON latest_opinion.id = (
                    SELECT candidate.id
                    FROM stock_opinion candidate
                    WHERE candidate.research_cycle_id = rc.id
                      AND candidate.withdrawn_at IS NULL
                    ORDER BY candidate.created_at DESC, candidate.id DESC
                    LIMIT 1
                )
            JOIN app_user latest_author ON latest_author.id = latest_opinion.author_user_id
            JOIN (
                SELECT
                    research_cycle_id,
                    COUNT(*) AS opinion_count,
                    COUNT(DISTINCT author_user_id) AS participant_count
                FROM stock_opinion
                GROUP BY research_cycle_id
            ) opinion_stats ON opinion_stats.research_cycle_id = rc.id
            WHERE ws.workspace_id = #{workspaceId}
            ORDER BY
                CASE rc.attention_level WHEN 'FOCUS' THEN 0 ELSE 1 END,
                rc.started_at DESC,
                ws.id DESC
            """)
    List<WorkspaceStockListRow> selectActiveList(Long workspaceId);

    @Select("""
            SELECT
                ws.id AS workspace_stock_id,
                s.code,
                s.name,
                s.exchange,
                s.currency,
                rc.id AS research_cycle_id,
                rc.status,
                rc.started_at,
                rc.ended_at
            FROM workspace_stock ws
            JOIN stock s ON s.id = ws.stock_id
            JOIN research_cycle rc ON rc.workspace_stock_id = ws.id
                AND rc.status IN ('ENDED', 'CANCELLED')
            LEFT JOIN research_cycle newer ON newer.workspace_stock_id = ws.id
                AND newer.status IN ('ENDED', 'CANCELLED')
                AND (newer.started_at > rc.started_at
                    OR (newer.started_at = rc.started_at AND newer.id > rc.id))
            WHERE ws.workspace_id = #{workspaceId}
              AND newer.id IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM research_cycle active_cycle
                  WHERE active_cycle.workspace_stock_id = ws.id
                    AND active_cycle.status = 'ACTIVE'
              )
            ORDER BY rc.ended_at DESC, ws.id DESC
            """)
    List<EndedWorkspaceStockRow> selectEndedList(Long workspaceId);
}
