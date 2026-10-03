package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongbridgeMarketCandlestickProviderTest {

    @Test
    void mapsValidatedGatewayDataAndMarksItAsRealLongbridgeData() {
        LongbridgeCandlestickGateway gateway = (code, period, count) -> List.of(
                new LongbridgeCandlestick(
                        Instant.parse("2026-09-04T07:00:00Z"),
                        new BigDecimal("1290"),
                        new BigDecimal("1305"),
                        new BigDecimal("1280"),
                        new BigDecimal("1298"),
                        123456,
                        new BigDecimal("159753000")
                )
        );

        MarketCandlestickSeries series = new LongbridgeMarketCandlestickProvider(gateway)
                .getCandlesticks("600519.SH", CandlestickPeriod.DAY, 120);

        assertThat(series.candlesticks()).hasSize(1);
        assertThat(series.source()).isEqualTo("LONGBRIDGE_OPENAPI");
        assertThat(series.simulated()).isFalse();
    }

    @Test
    void translatesProviderCode301607IntoTheDedicatedLimitFailure() {
        LongbridgeCandlestickGateway gateway = (code, period, count) -> {
            throw new LongbridgeUpstreamException("301607", "quota exceeded");
        };

        assertThatThrownBy(() -> new LongbridgeMarketCandlestickProvider(gateway)
                .getCandlesticks("600519.SH", CandlestickPeriod.DAY, 120))
                .isInstanceOf(MarketCandlestickLimitExceededException.class);
    }

    @Test
    void excludesTheCursorBarWhenLoadingOlderHistory() {
        Instant before = Instant.parse("2026-09-04T07:00:00Z");
        LongbridgeCandlestickGateway gateway = new LongbridgeCandlestickGateway() {
            @Override
            public List<LongbridgeCandlestick> getCandlesticks(
                    String code, CandlestickPeriod period, int count) {
                return List.of();
            }

            @Override
            public List<LongbridgeCandlestick> getCandlesticksBefore(
                    String code, CandlestickPeriod period, int count, Instant cursor) {
                return List.of(
                        bar("2026-09-03T07:00:00Z"),
                        bar("2026-09-04T07:00:00Z")
                );
            }
        };

        MarketCandlestickSeries series = new LongbridgeMarketCandlestickProvider(gateway)
                .getCandlesticksBefore("600519.SH", CandlestickPeriod.DAY, 2, before);

        assertThat(series.candlesticks()).extracting(MarketCandlestick::timestamp)
                .containsExactly(Instant.parse("2026-09-03T07:00:00Z"));
        assertThat(series.hasMore()).isTrue();
    }

    private LongbridgeCandlestick bar(String timestamp) {
        return new LongbridgeCandlestick(
                Instant.parse(timestamp),
                new BigDecimal("100"),
                new BigDecimal("101"),
                new BigDecimal("99"),
                new BigDecimal("100.5"),
                1000,
                new BigDecimal("100500")
        );
    }
}
