package com.stock.system.stock.vo;

import com.stock.system.stock.market.StockStatusInfo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockSearchResponseVO(
        String code,
        String name,
        String exchange,
        String currency,
        String listingStatus,
        BigDecimal price,
        LocalDateTime quoteTime,
        String source,
        boolean simulated,
        StockStatusInfo statusInfo
) {
}
