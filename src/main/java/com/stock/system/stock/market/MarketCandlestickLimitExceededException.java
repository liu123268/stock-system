package com.stock.system.stock.market;

public class MarketCandlestickLimitExceededException extends RuntimeException {

    public MarketCandlestickLimitExceededException(Throwable cause) {
        super("Longbridge candlestick request exceeded an interface limit", cause);
    }
}
