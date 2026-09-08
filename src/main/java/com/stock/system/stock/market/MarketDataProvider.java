package com.stock.system.stock.market;

import java.util.List;
import java.util.Optional;

public interface MarketDataProvider {

    List<StockQuoteSnapshot> search(String keyword);

    Optional<StockQuoteSnapshot> getLatestQuote(String code);
}
