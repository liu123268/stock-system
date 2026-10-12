package com.stock.system.stock.market;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in only: reads one already-approved symbol and never starts Spring or writes the database. */
class LongbridgeCandlestickRealProbeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "STOCK_LONGBRIDGE_KLINE_PROBE", matches = "true")
    void readsCandlesticksThroughTheOwnedCliSidecar() {
        try (LongbridgeJsonRpcClient client = new LongbridgeJsonRpcClient(
                new ObjectMapper(),
                () -> LongbridgeJsonRpcClient.startCliProcess("longbridge"),
                Duration.ofSeconds(8),
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                5,
                Duration.ofMillis(100)
        )) {
            var candlesticks = client.getCandlesticks(
                    "600519.SH", CandlestickPeriod.DAY, 2);

            assertThat(candlesticks).hasSize(2);
            assertThat(candlesticks)
                    .allSatisfy(item -> assertThat(item.close()).isPositive());
        }
    }
}
