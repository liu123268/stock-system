package com.stock.system.stock.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

interface LongbridgeCandlestickGateway {

    List<LongbridgeCandlestick> getCandlesticks(String symbol, CandlestickPeriod period, int count);

    default List<LongbridgeCandlestick> getCandlesticksBefore(
            String symbol,
            CandlestickPeriod period,
            int count,
            Instant before
    ) {
        throw new UnsupportedOperationException("Historical candlesticks are not implemented");
    }

    default List<LongbridgeCandlestick> getCandlesticksOnDate(
            String symbol,
            CandlestickPeriod period,
            LocalDate date
    ) {
        throw new UnsupportedOperationException("Dated candlesticks are not implemented");
    }
}
