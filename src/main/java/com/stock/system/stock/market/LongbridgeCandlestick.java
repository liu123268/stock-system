package com.stock.system.stock.market;

import java.math.BigDecimal;
import java.time.Instant;

record LongbridgeCandlestick(
        Instant timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        BigDecimal turnover,
        Instant retrievedAt
) {

    LongbridgeCandlestick(
            Instant timestamp,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume,
            BigDecimal turnover
    ) {
        this(timestamp, open, high, low, close, volume, turnover, Instant.now());
    }
}
