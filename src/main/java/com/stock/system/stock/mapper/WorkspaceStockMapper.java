package com.stock.system.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.stock.entity.WorkspaceStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkspaceStockMapper extends BaseMapper<WorkspaceStock> {

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
                opinion.risk_note,
                opinion.horizon,
                opinion.target_price,
                opinion.stop_loss_price,
                opinion.created_at AS opinion_created_at
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
}
