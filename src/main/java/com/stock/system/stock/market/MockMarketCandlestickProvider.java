package com.stock.system.stock.market;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "mock", matchIfMissing = true)
class MockMarketCandlestickProvider implements MarketCandlestickProvider {

    private static final Instant END = Instant.parse("2026-09-04T07:00:00Z");

    @Override
    public MarketCandlestickSeries getCandlesticks(
            String code,
            CandlestickPeriod period,
            int count
    ) {
        return series(period, count, END);
    }

    @Override
    public MarketCandlestickSeries getCandlesticksBefore(
            String code,
            CandlestickPeriod period,
            int count,
            Instant before
    ) {
        return series(period, count, before.minusSeconds(1));
    }

    private MarketCandlestickSeries series(
            CandlestickPeriod period,
            int count,
            Instant end
    ) {
        int size = Math.min(count, 240);
        List<MarketCandlestick> items = new ArrayList<>(size);
        BigDecimal base = new BigDecimal("100.0000");
        for (int index = 0; index < size; index++) {
            BigDecimal move = new BigDecimal(index).multiply(new BigDecimal("0.6500"));
            BigDecimal open = base.add(move);
            BigDecimal close = index % 2 == 0
                    ? open.add(new BigDecimal("1.2000"))
                    : open.subtract(new BigDecimal("0.8000"));
            BigDecimal high = open.max(close).add(new BigDecimal("0.9000"));
            BigDecimal low = open.min(close).subtract(new BigDecimal("0.7000"));
            long unitsBack = (long) (size - index - 1) * periodSeconds(period);
            items.add(new MarketCandlestick(
                    end.minus(unitsBack, ChronoUnit.SECONDS),
                    open,
                    high,
                    low,
                    close,
                    1_000_000L + index * 25_000L,
                    close.multiply(BigDecimal.valueOf(1_000_000L + index * 25_000L))
            ));
        }
        return new MarketCandlestickSeries(
                List.copyOf(items), "LOCAL_MOCK", true, END, size == count);
    }

    private long periodSeconds(CandlestickPeriod period) {
        return switch (period) {
            case MINUTE_1 -> 60;
            case MINUTE_5 -> 5 * 60;
            case MINUTE_15 -> 15 * 60;
            case MINUTE_30 -> 30 * 60;
            case HOUR_1 -> 60 * 60;
            case DAY -> 24 * 60 * 60;
            case WEEK -> 7 * 24 * 60 * 60;
            case MONTH -> 30 * 24 * 60 * 60;
            case YEAR -> 365 * 24 * 60 * 60;
        };
    }
}
