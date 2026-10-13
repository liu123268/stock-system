package com.stock.system.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.stock.entity.ResearchCycle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


@Mapper
public interface ResearchCycleMapper extends BaseMapper<ResearchCycle> {

    @Select("""
            select rc.*
            from research_cycle rc
            join workspace_stock ws on ws.id = rc.workspace_stock_id
            where ws.workspace_id = #{workspaceId}
              and ws.id = #{workspaceStockId}
              and rc.id = #{researchCycleId}
            """)
    ResearchCycle selectInWorkspaceStock(
            @Param("workspaceId") Long workspaceId,
            @Param("workspaceStockId") Long workspaceStockId,
            @Param("researchCycleId") Long researchCycleId
    );

    @Select("""
            SELECT rc.*
            FROM research_cycle rc
            JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
            WHERE ws.workspace_id = #{workspaceId}
              AND ws.id = #{workspaceStockId}
              AND rc.status = 'ACTIVE'
            FOR UPDATE
            """)
    ResearchCycle selectActiveForUpdate(
            @Param("workspaceId") Long workspaceId,
            @Param("workspaceStockId") Long workspaceStockId
    );

    @Select("""
            SELECT rc.*
            FROM research_cycle rc
            JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
            WHERE ws.workspace_id = #{workspaceId}
              AND ws.id = #{workspaceStockId}
              AND rc.status = 'ACTIVE'
            """)
    ResearchCycle selectActiveForStock(
            @Param("workspaceId") Long workspaceId,
            @Param("workspaceStockId") Long workspaceStockId
    );
}
