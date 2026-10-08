package com.stock.system.stock.market;

import com.stock.system.stock.entity.Stock;
import java.time.LocalDateTime;

/** Last provider observation and last attempt are distinct from the price timestamp. */
public record StockStatusInfo(String tradeStatus, String lastKnownListingStatus,
                              LocalDateTime checkedAt, LocalDateTime attemptedAt, boolean current) {
    public static StockStatusInfo from(Stock stock) {
        return new StockStatusInfo(stock.getTradeStatus(),
                stock.getStatusCheckedAt() == null ? null : stock.getListingStatus(),
                stock.getStatusCheckedAt(), stock.getStatusAttemptedAt(),
                StockStatusChecks.isCurrent(stock.getStatusCheckedAt(), stock.getStatusAttemptedAt()));
    }
}
