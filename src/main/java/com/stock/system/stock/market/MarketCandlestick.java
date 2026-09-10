package com.stock.system.stock.market;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketCandlestick(
        Instant timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        BigDecimal turnover
) {
}
