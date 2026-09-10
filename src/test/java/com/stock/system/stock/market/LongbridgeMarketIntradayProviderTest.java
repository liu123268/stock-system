package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                intradayGateway, candlestickGateway, (symbol, date) -> true,
                clockAt("2026-09-10T02:00:00Z"))
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

    @Test
    void rejectsExchangeHolidayBeforeRequestingIntradayPoints() {
        AtomicBoolean intradayRequested = new AtomicBoolean();
        LongbridgeIntradayGateway intradayGateway = symbol -> {
            intradayRequested.set(true);
            return List.of();
        };

        LongbridgeMarketIntradayProvider provider = new LongbridgeMarketIntradayProvider(
                intradayGateway,
                emptyCandlestickGateway(),
                (symbol, date) -> false,
                clockAt("2026-10-01T02:00:00Z")
        );

        assertThatThrownBy(() -> provider.getIntraday(
                "600519.SH", LocalDate.of(2026, 10, 1)))
                .isInstanceOf(MarketIntradayNonTradingDayException.class);
        assertThat(intradayRequested).isFalse();
    }

    @Test
    void excludesPreviousTradingDayPointsFromTodaysIntradaySeries() {
        LongbridgeIntradayGateway intradayGateway = symbol -> List.of(
                point("2026-09-09T07:00:00Z"),
                point("2026-09-10T01:31:00Z")
        );
        LongbridgeCandlestickGateway candlestickGateway = previousCloseGateway("18.80");

        MarketIntradaySeries series = new LongbridgeMarketIntradayProvider(
                intradayGateway,
                candlestickGateway,
                (symbol, date) -> true,
                clockAt("2026-09-10T02:00:00Z")
        ).getIntraday("600089.SH", LocalDate.of(2026, 9, 10));

        assertThat(series.points()).singleElement()
                .extracting(MarketIntradayPoint::timestamp)
                .isEqualTo(Instant.parse("2026-09-10T01:31:00Z"));
        assertThat(series.updating()).isTrue();
    }

    @Test
    void mapsProviderMinuteHistoryBoundaryToTypedRangeError() {
        LongbridgeCandlestickGateway candlestickGateway = new LongbridgeCandlestickGateway() {
            @Override
            public List<LongbridgeCandlestick> getCandlesticks(
                    String symbol, CandlestickPeriod period, int count) {
                return List.of();
            }

            @Override
            public List<LongbridgeCandlestick> getCandlesticksOnDate(
                    String symbol, CandlestickPeriod period, LocalDate date) {
                throw new LongbridgeCliException(
                        "301600", "Longbridge request rejected",
                        "out of minute candlestick limit begin date:2023-09-01");
            }
        };

        LongbridgeMarketIntradayProvider provider = new LongbridgeMarketIntradayProvider(
                symbol -> List.of(),
                candlestickGateway,
                (symbol, date) -> true,
                clockAt("2026-09-10T02:00:00Z")
        );

        assertThatThrownBy(() -> provider.getIntraday(
                "600089.SH", LocalDate.of(2023, 5, 9)))
                .isInstanceOfSatisfying(
                        MarketIntradayHistoryRangeExceededException.class,
                        exception -> assertThat(exception.getEarliestAvailableDate())
                                .isEqualTo(LocalDate.of(2023, 9, 1))
                );
    }

    private Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private LongbridgeIntradayPoint point(String timestamp) {
        return new LongbridgeIntradayPoint(
                Instant.parse(timestamp), new BigDecimal("19.00"),
                new BigDecimal("18.95"), 100, new BigDecimal("190000"));
    }

    private LongbridgeCandlestickGateway emptyCandlestickGateway() {
        return previousCloseGateway("18.80");
    }

    private LongbridgeCandlestickGateway previousCloseGateway(String close) {
        return new LongbridgeCandlestickGateway() {
            @Override
            public List<LongbridgeCandlestick> getCandlesticks(
                    String symbol, CandlestickPeriod period, int count) {
                return List.of();
            }

            @Override
            public List<LongbridgeCandlestick> getCandlesticksBefore(
                    String symbol, CandlestickPeriod period, int count, Instant before) {
                return List.of(bar("2026-09-09T07:00:00Z", close, 100, "188000"));
            }
        };
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
