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
                rc.id AS research_cycle_id,
                s.code,
                s.name,
                s.exchange,
                s.currency,
                s.listing_status,
                rc.attention_level,
                rc.started_by_user_id,
                starter.nickname AS started_by_nickname,
                rc.started_at,
                rc.start_price,
                rc.quote_time,
                rc.quote_source,
                rc.is_simulated AS simulated,
                opinion.id AS initial_opinion_id,
                opinion.author_user_id,
                author.nickname AS author_nickname,
                opinion.stance,
                opinion.reason,
                opinion.observation_note,
                opinion.horizon,
                opinion.target_price,
                opinion.stop_loss_price,
                opinion.created_at AS opinion_created_at,
                opinion.withdrawn_at IS NOT NULL AS initial_opinion_withdrawn
            FROM workspace_stock ws
            JOIN stock s ON s.id = ws.stock_id
            JOIN research_cycle rc
                ON rc.workspace_stock_id = ws.id
                AND rc.status = 'ACTIVE'
            JOIN stock_opinion opinion
                ON opinion.research_cycle_id = rc.id
                AND opinion.type = 'INITIAL'
            JOIN app_user starter ON starter.id = rc.started_by_user_id
            JOIN app_user author ON author.id = opinion.author_user_id
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
