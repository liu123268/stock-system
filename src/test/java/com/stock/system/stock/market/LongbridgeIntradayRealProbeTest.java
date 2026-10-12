package com.stock.system.stock.market;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only: reads today's points for one approved symbol and writes no database data. */
class LongbridgeIntradayRealProbeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "STOCK_LONGBRIDGE_INTRADAY_PROBE", matches = "true")
    void readsIntradayPointsThroughTheOwnedCliSidecar() {
        String executable = System.getProperty("stock.longbridge.executable", "longbridge");
        try (LongbridgeJsonRpcClient client = new LongbridgeJsonRpcClient(
                new ObjectMapper(),
                () -> LongbridgeJsonRpcClient.startCliProcess(executable),
                Duration.ofSeconds(8),
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                5,
                Duration.ofMillis(100)
        )) {
            var points = client.getIntraday("600519.SH");

            assertThat(points).isNotEmpty();
            assertThat(points).allSatisfy(point -> {
                assertThat(point.timestamp()).isNotNull();
                assertThat(point.price()).isPositive();
                assertThat(point.averagePrice()).isPositive();
                assertThat(point.volume()).isNotNegative();
            });

            var historicalMinutes = client.getCandlesticksOnDate(
                    "600519.SH", CandlestickPeriod.MINUTE_1, LocalDate.of(2026, 9, 9));
            assertThat(historicalMinutes).isNotEmpty();
            assertThat(historicalMinutes)
                    .allSatisfy(item -> assertThat(item.close()).isPositive());

            LocalDate tradeDate = LocalDate.of(2026, 9, 10);
            ZoneId marketZone = MarketTradingSessions.zoneFor("600519.SH");
            var previousDailyBar = client.getCandlesticksBefore(
                            "600519.SH",
                            CandlestickPeriod.DAY,
                            10,
                            tradeDate.atStartOfDay(marketZone).toInstant()).stream()
                    .filter(item -> item.timestamp().atZone(marketZone).toLocalDate()
                            .isBefore(tradeDate))
                    .max(Comparator.comparing(LongbridgeCandlestick::timestamp));
            assertThat(previousDailyBar).isPresent();
            assertThat(previousDailyBar.orElseThrow().close()).isPositive();
        }
    }
}
