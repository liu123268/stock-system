package com.stock.system.stock.market;

import java.util.Arrays;

public enum CandlestickPeriod {
    MINUTE_1("1m"),
    MINUTE_5("5m"),
    MINUTE_15("15m"),
    MINUTE_30("30m"),
    HOUR_1("1h"),
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    YEAR("year");

    private final String apiValue;

    CandlestickPeriod(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static CandlestickPeriod fromApiValue(String value) {
        return Arrays.stream(values())
                .filter(period -> period.apiValue.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported candlestick period"));
    }
}
