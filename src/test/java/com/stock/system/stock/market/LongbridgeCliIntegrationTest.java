package com.stock.system.stock.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongbridgeCliIntegrationTest {

    @Test
    @EnabledIfSystemProperty(named = "stock.longbridge.cli.integration", matches = "true")
    void pullsARealQuoteThroughTheManagedSidecar() {
        String executable = System.getProperty("stock.longbridge.cli.executable", "longbridge");
        try (LongbridgeJsonRpcClient client = new LongbridgeJsonRpcClient(
                new ObjectMapper(),
                () -> LongbridgeJsonRpcClient.startCliProcess(executable),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                5,
                Duration.ofMillis(100)
        )) {
            LongbridgeQuote quote = client.getQuotes(List.of("600519.SH")).get("600519.SH");
            assertThat(quote).isNotNull();
            assertThat(quote.lastDone()).isPositive();
            assertThat(quote.timestamp()).isNotNull();
        }
    }
}
