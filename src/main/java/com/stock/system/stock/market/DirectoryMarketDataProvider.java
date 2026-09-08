package com.stock.system.stock.market;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

/** Directory mode deliberately has no quote fallback until a real quote source is connected. */
@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "directory")
public class DirectoryMarketDataProvider implements MarketDataProvider {
    @Override
    public List<StockQuoteSnapshot> search(String keyword) {
        throw new UnsupportedOperationException("Directory search belongs to StockMapper");
    }

    @Override
    public Optional<StockQuoteSnapshot> getLatestQuote(String code) {
        return Optional.empty();
    }
}
