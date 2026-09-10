package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongbridgeMarketIntradayProviderTest {

    @Test
    void reconstructsHistoricalAveragePriceFromMinuteCandlesticks() {
        LongbridgeIntradayGateway intradayGateway = symbol -> List.of();
        LongbridgeCandlestickGateway candlestickGateway = new LongbridgeCandlestickGateway() {
            @Override
            public List<LongbridgeCandlestick> getCandlesticks(
                    String symbol, CandlestickPeriod period, int count) {
                return List.of();
            }

            @Override
            public List<LongbridgeCandlestick> getCandlesticksOnDate(
                    String symbol, CandlestickPeriod period, LocalDate date) {
                assertThat(period).isEqualTo(CandlestickPeriod.MINUTE_1);
                return List.of(
                        bar("2020-01-01T07:00:00Z", "99", 100, "990000"),
                        bar("2020-01-02T01:30:00Z", "10", 100, "100000"),
                        bar("2020-01-02T01:31:00Z", "20", 100, "200000")
                );
            }

            @Override
            public List<LongbridgeCandlestick> getCandlesticksBefore(
                    String symbol,
                    CandlestickPeriod period,
                    int count,
                    Instant before
            ) {
                assertThat(period).isEqualTo(CandlestickPeriod.DAY);
                assertThat(count).isEqualTo(10);
                return List.of(
                        bar("2019-12-31T07:00:00Z", "8", 100, "80000"),
                        bar("2020-01-01T07:00:00Z", "9", 100, "90000"),
                        bar("2020-01-02T07:00:00Z", "20", 100, "200000")
                );
            }
        };

        MarketIntradaySeries series = new LongbridgeMarketIntradayProvider(
                intradayGateway, candlestickGateway)
                .getIntraday("600519.SH", LocalDate.of(2020, 1, 2));

        assertThat(series.updating()).isFalse();
        assertThat(series.tradingSessions()).containsExactly(
                new MarketTradingSession(java.time.LocalTime.of(9, 30), java.time.LocalTime.of(11, 30)),
                new MarketTradingSession(java.time.LocalTime.of(13, 0), java.time.LocalTime.of(15, 0))
        );
        assertThat(series.points()).hasSize(2);
        assertThat(series.referencePrice()).isEqualByComparingTo("9");
        assertThat(series.points().get(0).averagePrice()).isEqualByComparingTo("10.0000");
        assertThat(series.points().get(1).averagePrice()).isEqualByComparingTo("15.0000");
    }

    private LongbridgeCandlestick bar(
            String timestamp,
            String close,
            long volume,
            String turnover
    ) {
        BigDecimal price = new BigDecimal(close);
        return new LongbridgeCandlestick(
                Instant.parse(timestamp), price, price, price, price, volume,
                new BigDecimal(turnover));
    }
}
