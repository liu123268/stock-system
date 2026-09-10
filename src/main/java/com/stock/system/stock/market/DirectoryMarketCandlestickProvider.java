package com.stock.system.stock.market;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "directory")
class DirectoryMarketCandlestickProvider implements MarketCandlestickProvider {

    @Override
    public MarketCandlestickSeries getCandlesticks(
            String code,
            CandlestickPeriod period,
            int count
    ) {
        throw new MarketCandlestickUnavailableException(
                "Directory-only mode does not provide candlesticks");
    }

    @Override
    public MarketCandlestickSeries getCandlesticksBefore(
            String code,
            CandlestickPeriod period,
            int count,
            Instant before
    ) {
        throw new MarketCandlestickUnavailableException(
                "Directory-only mode does not provide candlesticks");
    }
}
