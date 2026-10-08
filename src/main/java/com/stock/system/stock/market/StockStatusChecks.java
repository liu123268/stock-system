package com.stock.system.stock.market;

import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.StockMapper;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** Provider facts only. Network calls occur before these short, conditional writes. */
@Service
public class StockStatusChecks {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final StockMapper mapper;

    public StockStatusChecks(StockMapper mapper) { this.mapper = mapper; }

    public void observe(Stock stock, LongbridgeQuote quote, Instant attemptedAt) {
        String raw = quote == null ? null : LongbridgeTradeStatus.normalize(quote.tradeStatus());
        LocalDateTime attempt = time(attemptedAt);
        LocalDateTime observed = quote == null || quote.retrievedAt() == null ? null : time(quote.retrievedAt());
        if (raw != null && observed != null && !observed.isAfter(attempt.plusMinutes(1))) {
            if (stock.getStatusAttemptedAt() == null || !observed.isBefore(stock.getStatusAttemptedAt())) {
                int written = mapper.recordStatus(stock.getId(), LongbridgeTradeStatus.listingStatus(raw), raw, observed);
                if (written == 0 && stock.getId() != null) {
                    reload(stock);
                    return;
                }
                stock.setListingStatus(LongbridgeTradeStatus.listingStatus(raw));
                stock.setTradeStatus(raw);
                stock.setStatusCheckedAt(observed);
                stock.setStatusAttemptedAt(observed);
            }
        } else if (stock.getStatusAttemptedAt() == null || attempt.isAfter(stock.getStatusAttemptedAt())) {
            int written = mapper.recordStatusFailure(stock.getId(), attempt);
            if (written == 0 && stock.getId() != null) { reload(stock); return; }
            stock.setStatusAttemptedAt(attempt);
        }
    }

    private void reload(Stock stock) {
        Stock latest = mapper.selectById(stock.getId());
        if (latest == null) return;
        stock.setListingStatus(latest.getListingStatus()); stock.setTradeStatus(latest.getTradeStatus());
        stock.setStatusCheckedAt(latest.getStatusCheckedAt()); stock.setStatusAttemptedAt(latest.getStatusAttemptedAt());
    }

    public static String currentStatus(Stock stock) {
        if ("LOCAL_MOCK".equals(stock.getSource())) return stock.getListingStatus();
        return isCurrent(stock.getStatusCheckedAt(), stock.getStatusAttemptedAt())
                ? stock.getListingStatus() : "UNKNOWN";
    }

    public static boolean isCurrent(LocalDateTime checked, LocalDateTime attempted) {
        LocalDateTime now = time(Instant.now());
        return checked != null && attempted != null && !attempted.isAfter(checked)
                && !checked.isBefore(now.minusHours(36)) && !checked.isAfter(now.plusMinutes(1));
    }

    public static LocalDateTime time(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZONE).truncatedTo(ChronoUnit.MILLIS);
    }
}
