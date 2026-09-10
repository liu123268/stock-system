package com.stock.system.stock.market;

public class MarketIntradayUnavailableException extends RuntimeException {

    public MarketIntradayUnavailableException(String message) {
        super(message);
    }

    public MarketIntradayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
