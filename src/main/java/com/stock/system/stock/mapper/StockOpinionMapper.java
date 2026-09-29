package com.stock.system.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.stock.entity.StockOpinion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


@Mapper
public interface StockOpinionMapper extends BaseMapper<StockOpinion> {

    @Select("""
            SELECT opinion.*
            FROM stock_opinion opinion
            JOIN research_cycle rc ON rc.id = opinion.research_cycle_id
            JOIN workspace_stock ws ON ws.id = rc.workspace_stock_id
            WHERE ws.workspace_id = #{workspaceId}
              AND ws.id = #{workspaceStockId}
              AND opinion.id = #{opinionId}
            FOR UPDATE
            """)
    StockOpinion selectForUpdate(
            @Param("workspaceId") Long workspaceId,
            @Param("workspaceStockId") Long workspaceStockId,
            @Param("opinionId") Long opinionId
    );
}
