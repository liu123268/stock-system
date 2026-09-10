package com.stock.system.stock.market;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;

public record MarketIntradaySeries(
        LocalDate tradeDate,
        String marketTimeZone,
        List<MarketTradingSession> tradingSessions,
        BigDecimal referencePrice,
        boolean updating,
        List<MarketIntradayPoint> points,
        String source,
        boolean simulated,
        Instant retrievedAt
) {
}
