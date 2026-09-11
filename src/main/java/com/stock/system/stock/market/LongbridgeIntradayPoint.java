package com.stock.system.stock.market;

import java.math.BigDecimal;
import java.time.Instant;

record LongbridgeIntradayPoint(
        Instant timestamp,
        BigDecimal price,
        BigDecimal averagePrice,
        long volume,
        BigDecimal turnover,
        Instant retrievedAt
) {

    LongbridgeIntradayPoint(
            Instant timestamp,
            BigDecimal price,
            BigDecimal averagePrice,
            long volume,
            BigDecimal turnover
    ) {
        this(timestamp, price, averagePrice, volume, turnover, Instant.now());
    }
}
