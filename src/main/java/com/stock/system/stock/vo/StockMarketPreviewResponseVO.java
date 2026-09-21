package com.stock.system.stock.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockMarketPreviewResponseVO(
        String code,
        String name,
        String exchange,
        String currency,
        String listingStatus,
        BigDecimal price,
        LocalDateTime quoteTime,
        String source,
        boolean simulated,
        String trackingState,
        Long workspaceStockId,
        Long activeResearchCycleId
) {
}
