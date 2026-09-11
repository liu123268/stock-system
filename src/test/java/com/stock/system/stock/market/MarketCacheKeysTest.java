package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketCacheKeysTest {

    @Test
    void quoteKeyIsOrderAndCaseIndependent() {
        String first = MarketCacheKeys.quotes(
                "market:v1", List.of("600519.SH", "00700.HK"));
        String second = MarketCacheKeys.quotes(
                "market:v1", List.of("00700.hk", "600519.sh"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void historicalBoundaryDistinguishesTwoTimesOnTheSameDate() {
        String morning = MarketCacheKeys.candlesticksBefore(
                "market:v1", "600519.SH", CandlestickPeriod.MINUTE_1, 1000,
                Instant.parse("2026-09-11T02:00:00Z"));
        String afternoon = MarketCacheKeys.candlesticksBefore(
                "market:v1", "600519.SH", CandlestickPeriod.MINUTE_1, 1000,
                Instant.parse("2026-09-11T06:00:00Z"));

        assertThat(morning).isNotEqualTo(afternoon);
    }

    @Test
    void intradayBoundaryIncludesTheMarketDate() {
        String firstDay = MarketCacheKeys.intraday(
                "market:v1", "600519.SH", LocalDate.of(2026, 9, 10));
        String nextDay = MarketCacheKeys.intraday(
                "market:v1", "600519.SH", LocalDate.of(2026, 9, 11));

        assertThat(firstDay).isNotEqualTo(nextDay);
    }
}
