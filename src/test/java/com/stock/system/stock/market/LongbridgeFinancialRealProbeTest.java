package com.stock.system.stock.market;

import com.stock.system.stock.financial.FinancialMetric;
import com.stock.system.stock.financial.FinancialPeriod;
import com.stock.system.stock.financial.LongbridgeFinancialParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit source-only acceptance: existing SDK OAuth, two public reads, no quote/database/model. */
@EnabledIfEnvironmentVariable(named = "STOCK_LONGBRIDGE_FINANCIAL_REAL_PROBE", matches = "true")
class LongbridgeFinancialRealProbeTest {
    @Test void realSdkAdapterAndParserRetainUnverifiedBasisForBothMarkets() throws Exception {
        Properties config = new Properties();
        try (var input = Files.newBufferedReader(Path.of(".env.longbridge-local.properties"), StandardCharsets.UTF_8)) {
            config.load(input);
        }
        String clientId = config.getProperty("local.longbridge.client-id", "").trim();
        var parser = new LongbridgeFinancialParser(new ObjectMapper());
        try (var client = new LongbridgeSdkFinancialClient(clientId, Duration.ofSeconds(20), Duration.ofMillis(1500))) {
            for (String symbol : List.of("600519.SH", "700.HK")) {
                var period = symbol.endsWith(".HK") ? FinancialPeriod.QUARTER : FinancialPeriod.ANNUAL;
                var source = client.readFinancialReport(symbol, period);
                var facts = parser.parse(symbol, period, null, List.of(FinancialMetric.values()), source);
                assertThat(facts.status()).isEqualTo("PARTIAL");
                assertThat(facts.facts()).hasSize(5);
                assertThat(facts.facts()).allSatisfy(fact -> {
                    assertThat(fact.current().amount()).isNotNull();
                    assertThat(fact.change().percent()).isNull();
                });
                System.out.println("FINANCIAL_ADAPTER_REAL_OK symbol=" + symbol + " series=" + period
                        + " fields=5 comparisonEnabled=false quoteContext=false database=false model=false");
            }
        } catch (Exception | LinkageError failure) {
            // Provider errors and SDK OAuth URLs cannot leak through a test report.
            throw new AssertionError("FINANCIAL_ADAPTER_REAL_FAILED type=" + failure.getClass().getSimpleName());
        }
    }
}
