package com.stock.system.stock.market;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "mock", matchIfMissing = true)
public class MockMarketDataProvider implements MarketDataProvider {

    private static final String SOURCE = "LOCAL_MOCK";
    private static final LocalDateTime QUOTE_TIME = LocalDateTime.of(2026, 9, 4, 15, 0);

    private static final List<StockQuoteSnapshot> STOCKS = List.of(
            quote("600519.SH", "贵州茅台", "SSE", "CNY", "1488.8800"),
            quote("300750.SZ", "宁德时代", "SZSE", "CNY", "268.5000"),
            quote("00700.HK", "腾讯控股", "HKEX", "HKD", "612.0000"),
            quote("09988.HK", "阿里巴巴-W", "HKEX", "HKD", "118.8000")
    );

    @Override
    public List<StockQuoteSnapshot> search(String keyword) {
        String normalized = keyword.toLowerCase(Locale.ROOT);
        return STOCKS.stream()
                .filter(stock -> stock.code().toLowerCase(Locale.ROOT).contains(normalized)
                        || stock.name().toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
    }

    @Override
    public Optional<StockQuoteSnapshot> getLatestQuote(String code) {
        return STOCKS.stream()
                .filter(stock -> stock.code().equalsIgnoreCase(code))
                .findFirst();
    }

    private static StockQuoteSnapshot quote(
            String code,
            String name,
            String exchange,
            String currency,
            String price
    ) {
        return new StockQuoteSnapshot(
                code,
                name,
                exchange,
                currency,
                "LISTED",
                new BigDecimal(price),
                QUOTE_TIME,
                SOURCE,
                true
        );
    }
}
