package com.stock.system.stock.market;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketIntradayPoint(
        Instant timestamp,
        BigDecimal price,
        BigDecimal averagePrice,
        long volume,
        BigDecimal turnover
) {
}
