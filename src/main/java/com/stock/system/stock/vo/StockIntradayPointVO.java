package com.stock.system.stock.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockIntradayPointVO(
        LocalDateTime time,
        BigDecimal price,
        BigDecimal averagePrice,
        long volume,
        BigDecimal turnover
) {
}
