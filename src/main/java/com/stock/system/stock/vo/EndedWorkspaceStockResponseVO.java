package com.stock.system.stock.vo;

import java.time.LocalDateTime;

public record EndedWorkspaceStockResponseVO(
        Long workspaceStockId,
        String code,
        String name,
        String exchange,
        String currency,
        Long researchCycleId,
        String status,
        LocalDateTime startedAt,
        LocalDateTime endedAt
) {
}
