package com.stock.system.stock.vo;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;

public record StockCandlestickSeriesVO(
        String code,
        String name,
        String currency,
        String period,
        String adjustment,
        String source,
        boolean simulated,
        LocalDateTime retrievedAt,
        Instant nextBefore,
        boolean hasMore,
        List<StockCandlestickVO> candlesticks
) {
}
