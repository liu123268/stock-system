package com.stock.system.stock.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockCandlestickVO(
        LocalDateTime time,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        BigDecimal turnover
) {
}
