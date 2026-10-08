package com.stock.system.stock.market;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "longbridge")
public class LongbridgeMarketDataProvider implements MarketDataProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(LongbridgeMarketDataProvider.class);
    private static final String SOURCE = "LONGBRIDGE_OPENAPI";
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final StockMapper stockMapper;
    private final LongbridgeQuoteGateway quoteGateway;
    private final StockStatusChecks statusChecks;

    LongbridgeMarketDataProvider(StockMapper stockMapper, LongbridgeQuoteGateway quoteGateway) {
        this(stockMapper, quoteGateway, new StockStatusChecks(stockMapper));
    }

    @Autowired
    LongbridgeMarketDataProvider(StockMapper stockMapper, LongbridgeQuoteGateway quoteGateway,
                                 StockStatusChecks statusChecks) {
        this.stockMapper = stockMapper;
        this.quoteGateway = quoteGateway;
        this.statusChecks = statusChecks;
    }

    @Override
    public List<StockQuoteSnapshot> search(String keyword) {
        List<Stock> stocks = stockMapper.searchDirectory(keyword);
        if (stocks.isEmpty()) {
            return List.of();
        }
        java.time.Instant attemptedAt = java.time.Instant.now();
        Map<String, LongbridgeQuote> quotes;
        try {
            quotes = quoteGateway.getQuotes(stocks.stream().map(Stock::getCode).toList());
        } catch (LongbridgeUpstreamException exception) {
            LOGGER.warn("Longbridge quotes are unavailable during stock search: {}", exception.getMessage());
            quotes = Map.of();
        }
        Map<String, LongbridgeQuote> availableQuotes = quotes;
        return stocks.stream()
                .map(stock -> {
                    LongbridgeQuote quote = availableQuotes.get(stock.getCode());
                    statusChecks.observe(stock, quote, attemptedAt);
                    return toSnapshot(stock, quote);
                })
                .toList();
    }

    @Override
    public Optional<StockQuoteSnapshot> getLatestQuote(String code) {
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        Stock stock = stockMapper.selectOne(Wrappers.<Stock>lambdaQuery()
                .eq(Stock::getCode, normalizedCode)
                .ne(Stock::getSource, "LOCAL_MOCK"));
        if (stock == null) {
            return Optional.empty();
        }
        java.time.Instant attemptedAt = java.time.Instant.now();
        try {
            LongbridgeQuote quote = quoteGateway.getQuotes(List.of(normalizedCode)).get(normalizedCode);
            statusChecks.observe(stock, quote, attemptedAt);
            return Optional.of(toSnapshot(stock, quote));
        } catch (LongbridgeUpstreamException exception) {
            LOGGER.warn("Longbridge quote is unavailable for {}: {}", normalizedCode, exception.getMessage());
            statusChecks.observe(stock, null, attemptedAt);
            return Optional.of(toSnapshot(stock, null));
        }
    }

    private StockQuoteSnapshot toSnapshot(Stock stock, LongbridgeQuote quote) {
        boolean usable = isUsable(quote);
        LocalDateTime quoteTime = usable
                ? LocalDateTime.ofInstant(quote.timestamp(), PROJECT_ZONE)
                : null;
        return new StockQuoteSnapshot(
                stock.getCode(),
                stock.getName(),
                stock.getExchange(),
                stock.getCurrency(),
                StockStatusChecks.currentStatus(stock),
                usable ? quote.lastDone() : null,
                quoteTime,
                quote != null ? SOURCE : stock.getSource(),
                false,
                StockStatusInfo.from(stock)
        );
    }

    private boolean isUsable(LongbridgeQuote quote) {
        return quote != null
                && quote.lastDone() != null
                && quote.lastDone().signum() > 0
                && quote.timestamp() != null
                && quote.timestamp().isAfter(java.time.Instant.EPOCH);
    }

}
