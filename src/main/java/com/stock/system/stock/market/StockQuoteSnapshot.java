package com.stock.system.stock.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockQuoteSnapshot(
        String code,
        String name,
        String exchange,
        String currency,
        String listingStatus,
        BigDecimal price,
        LocalDateTime quoteTime,
        String source,
        boolean simulated
) {
}
