package com.stock.system.stock.market;

import java.math.BigDecimal;
import java.time.Instant;

record LongbridgeQuote(
        String symbol,
        BigDecimal lastDone,
        Instant timestamp,
        String tradeStatus,
        Instant retrievedAt
) {

    LongbridgeQuote(String symbol, BigDecimal lastDone, Instant timestamp, String tradeStatus) {
        this(symbol, lastDone, timestamp, tradeStatus, Instant.now());
    }
}
