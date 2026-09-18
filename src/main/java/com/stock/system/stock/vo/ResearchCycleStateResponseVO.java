package com.stock.system.stock.vo;

import java.time.LocalDateTime;

public record ResearchCycleStateResponseVO(
        Long workspaceStockId,
        Long researchCycleId,
        String status,
        LocalDateTime endedAt
) {
}
