package com.stock.system.stock.vo;

public record AddWorkspaceStockResponseVO(
        Long workspaceStockId,
        Long researchCycleId,
        boolean alreadyActive
) {
}
