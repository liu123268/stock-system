package com.stock.system.stock.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

public record StockIntradaySeriesVO(
        String code,
        String name,
        String currency,
        LocalDate tradeDate,
        String marketTimeZone,
        List<StockTradingSessionVO> tradingSessions,
        BigDecimal referencePrice,
        boolean updating,
        String source,
        boolean simulated,
        LocalDateTime retrievedAt,
        List<StockIntradayPointVO> points
) {
}
