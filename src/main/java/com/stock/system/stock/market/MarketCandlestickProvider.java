package com.stock.system.stock.market;

import java.time.Instant;

public interface MarketCandlestickProvider {

    MarketCandlestickSeries getCandlesticks(String code, CandlestickPeriod period, int count);

    MarketCandlestickSeries getCandlesticksBefore(
            String code,
            CandlestickPeriod period,
            int count,
            Instant before
    );
}
