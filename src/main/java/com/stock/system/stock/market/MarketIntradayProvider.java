package com.stock.system.stock.market;

import java.time.LocalDate;

public interface MarketIntradayProvider {

    MarketIntradaySeries getIntraday(String code, LocalDate tradeDate);
}
