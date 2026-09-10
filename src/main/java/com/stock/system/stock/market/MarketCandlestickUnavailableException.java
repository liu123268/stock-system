package com.stock.system.stock.market;

public class MarketCandlestickUnavailableException extends RuntimeException {

    public MarketCandlestickUnavailableException(String message) {
        super(message);
    }

    public MarketCandlestickUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
