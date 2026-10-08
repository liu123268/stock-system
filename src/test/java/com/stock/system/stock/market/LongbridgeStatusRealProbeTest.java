package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit, quote-only read: no Spring, database or candlestick subscription. */
class LongbridgeStatusRealProbeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "STOCK_LONGBRIDGE_STATUS_REAL_PROBE", matches = "true")
    void observesStatusCoverageWithoutDatabase() {
        int returned = 0;
        try (var client = new LongbridgeSdkClient(System.getenv("LONGBRIDGE_CLIENT_ID"),
                Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMillis(200))) {
            for (String symbol : List.of("600519.SH", "000001.SZ", "920799.BJ", "00700.HK",
                    "00444.HK", "03333.HK", "000004.SZ")) {
                try {
                    var quote = client.getQuotes(List.of(symbol)).get(symbol);
                    if (quote != null) returned++;
                    System.out.println("STATUS_REAL symbol=" + symbol + " status="
                            + (quote == null ? "NO_RECORD" : LongbridgeTradeStatus.normalize(quote.tradeStatus()))
                            + " hasPrice=" + (quote != null && quote.lastDone() != null)
                            + " quoteTime=" + (quote == null ? null : quote.timestamp()));
                } catch (LongbridgeUpstreamException failure) {
                    System.out.println("STATUS_REAL symbol=" + symbol + " unavailable=true");
                }
            }
            var mixed = client.getQuotes(List.of("600519.SH", "920799.BJ", "03333.HK"));
            assertThat(mixed).containsKeys("600519.SH", "03333.HK");
            System.out.println("STATUS_REAL_MIXED returned=" + mixed.keySet());
        }
        assertThat(returned).as("at least one authorized quote must be returned").isPositive();
    }
}
