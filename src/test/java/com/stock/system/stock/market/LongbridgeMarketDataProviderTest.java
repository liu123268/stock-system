package com.stock.system.stock.market;

import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.StockMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongbridgeMarketDataProviderTest {

    private final StockMapper stockMapper = mock(StockMapper.class);
    private final LongbridgeQuoteGateway quoteGateway = mock(LongbridgeQuoteGateway.class);
    private final LongbridgeMarketDataProvider provider = new LongbridgeMarketDataProvider(stockMapper, quoteGateway);

    @Test
    void enrichesDirectorySearchWithOneBatchQuoteRequest() {
        Stock aShare = stock("600519.SH", "贵州茅台", "SSE", "CNY", "SSE_DIRECTORY");
        Stock hongKong = stock("00700.HK", "腾讯控股", "HKEX", "HKD", "HKEX_DIRECTORY");
        when(stockMapper.searchDirectory("股")).thenReturn(List.of(aShare, hongKong));
        when(quoteGateway.getQuotes(List.of("600519.SH", "00700.HK"))).thenReturn(Map.of(
                "600519.SH", quote("600519.SH", "1290.88", "2026-09-09T07:00:00Z"),
                "00700.HK", quote("00700.HK", "612.00", "2026-09-09T06:59:00Z")
        ));

        List<StockQuoteSnapshot> snapshots = provider.search("股");
        assertThat(snapshots)
                .hasSize(2)
                .allSatisfy(snapshot -> {
                    assertThat(snapshot.price()).isPositive();
                    assertThat(snapshot.source()).isEqualTo("LONGBRIDGE_OPENAPI");
                    assertThat(snapshot.listingStatus()).isEqualTo("LISTED");
                    assertThat(snapshot.simulated()).isFalse();
                });
        assertThat(snapshots.get(0).quoteTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 9, 15, 0));
        verify(quoteGateway).getQuotes(List.of("600519.SH", "00700.HK"));
    }

    @Test
    void keepsDirectoryCandidateButNoPriceWhenCliIsUnavailable() {
        Stock stock = stock("600519.SH", "贵州茅台", "SSE", "CNY", "SSE_DIRECTORY");
        when(stockMapper.searchDirectory("茅台")).thenReturn(List.of(stock));
        when(quoteGateway.getQuotes(any())).thenThrow(new LongbridgeCliException("sidecar unavailable"));

        assertThat(provider.search("茅台")).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.price()).isNull();
            assertThat(snapshot.quoteTime()).isNull();
            assertThat(snapshot.source()).isEqualTo("SSE_DIRECTORY");
            assertThat(snapshot.simulated()).isFalse();
        });
    }

    @Test
    void returnsEmptyWithoutCallingCliWhenCodeIsNotInTrustedDirectory() {
        when(stockMapper.selectOne(any())).thenReturn(null);

        assertThat(provider.getLatestQuote("000000.SH")).isEmpty();
        verify(quoteGateway, never()).getQuotes(any());
    }

    @Test
    void mapsOnlySupportedLongbridgeTradingStatuses() {
        Stock stock = stock("600519.SH", "贵州茅台", "SSE", "CNY", "SSE_DIRECTORY");
        when(stockMapper.searchDirectory("茅台")).thenReturn(List.of(stock));

        when(quoteGateway.getQuotes(any())).thenReturn(Map.of(
                "600519.SH", quote("600519.SH", "1290.88", "2026-09-09T07:00:00Z", "Halted")
        ));
        assertThat(provider.search("茅台")).singleElement()
                .extracting(StockQuoteSnapshot::listingStatus)
                .isEqualTo("SUSPENDED");

        when(quoteGateway.getQuotes(any())).thenReturn(Map.of(
                "600519.SH", quote("600519.SH", "1290.88", "2026-09-09T07:00:00Z", "Delisted")
        ));
        assertThat(provider.search("茅台")).singleElement()
                .extracting(StockQuoteSnapshot::listingStatus)
                .isEqualTo("DELISTED");

        when(quoteGateway.getQuotes(any())).thenReturn(Map.of(
                "600519.SH", quote("600519.SH", "1290.88", "2026-09-09T07:00:00Z", "CodeMoved")
        ));
        assertThat(provider.search("茅台")).singleElement()
                .extracting(StockQuoteSnapshot::listingStatus)
                .isEqualTo("UNKNOWN");
    }

    private Stock stock(String code, String name, String exchange, String currency, String source) {
        Stock stock = new Stock();
        stock.setCode(code);
        stock.setName(name);
        stock.setExchange(exchange);
        stock.setCurrency(currency);
        stock.setListingStatus("UNKNOWN");
        stock.setSource(source);
        return stock;
    }

    private LongbridgeQuote quote(String symbol, String price, String timestamp) {
        return quote(symbol, price, timestamp, "Normal");
    }

    private LongbridgeQuote quote(String symbol, String price, String timestamp, String tradeStatus) {
        return new LongbridgeQuote(symbol, new BigDecimal(price), Instant.parse(timestamp), tradeStatus);
    }
}
