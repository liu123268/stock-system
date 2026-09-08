package com.stock.system.stock;

import com.stock.system.stock.market.MockMarketDataProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockMarketDataProviderTest {

    private final MockMarketDataProvider provider = new MockMarketDataProvider();

    @Test
    void searchesAStockAndHongKongStockByNameOrCode() {
        assertThat(provider.search("茅台"))
                .singleElement()
                .satisfies(stock -> {
                    assertThat(stock.code()).isEqualTo("600519.SH");
                    assertThat(stock.currency()).isEqualTo("CNY");
                    assertThat(stock.simulated()).isTrue();
                });

        assertThat(provider.search("00700"))
                .singleElement()
                .satisfies(stock -> {
                    assertThat(stock.name()).isEqualTo("腾讯控股");
                    assertThat(stock.exchange()).isEqualTo("HKEX");
                    assertThat(stock.currency()).isEqualTo("HKD");
                });
    }

    @Test
    void getsLatestQuoteByCanonicalCodeAndReturnsEmptyForUnknownCode() {
        assertThat(provider.getLatestQuote("300750.sz"))
                .hasValueSatisfying(stock -> assertThat(stock.code()).isEqualTo("300750.SZ"));
        assertThat(provider.getLatestQuote("000000.SH")).isEmpty();
    }
}
