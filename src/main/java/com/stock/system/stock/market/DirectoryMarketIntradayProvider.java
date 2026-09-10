package com.stock.system.stock.market;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "directory")
class DirectoryMarketIntradayProvider implements MarketIntradayProvider {

    @Override
    public MarketIntradaySeries getIntraday(String code, LocalDate tradeDate) {
        throw new MarketIntradayUnavailableException(
                "Directory-only mode does not provide intraday data");
    }
}
