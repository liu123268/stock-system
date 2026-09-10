package com.stock.system.stock.market;

import java.time.LocalDate;

public class MarketIntradayHistoryRangeExceededException extends RuntimeException {

    private final LocalDate earliestAvailableDate;

    public MarketIntradayHistoryRangeExceededException(LocalDate earliestAvailableDate, Throwable cause) {
        super(cause);
        this.earliestAvailableDate = earliestAvailableDate;
    }

    public LocalDate getEarliestAvailableDate() {
        return earliestAvailableDate;
    }
}
