package com.stock.system.stock.market;

import java.time.Instant;
import java.util.List;

public record MarketCandlestickSeries(
        List<MarketCandlestick> candlesticks,
        String source,
        boolean simulated,
        Instant retrievedAt,
        boolean hasMore
) {
}
