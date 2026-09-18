package com.stock.system.stock.vo;

import java.util.List;

public record WorkspaceStockResearchResponseVO(
        Long workspaceStockId,
        String code,
        String name,
        String exchange,
        String currency,
        String listingStatus,
        List<ResearchCycleDetailResponseVO> cycles
) {
}
