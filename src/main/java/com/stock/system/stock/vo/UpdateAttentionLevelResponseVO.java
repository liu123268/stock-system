package com.stock.system.stock.vo;

public record UpdateAttentionLevelResponseVO(
        Long workspaceStockId,
        Long researchCycleId,
        String attentionLevel
) {
}
