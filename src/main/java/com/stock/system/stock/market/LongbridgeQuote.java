package com.stock.system.stock.market;

import java.math.BigDecimal;
import java.time.Instant;

record LongbridgeQuote(
        String symbol,
        BigDecimal lastDone,
        Instant timestamp,
        String tradeStatus
) {
}
