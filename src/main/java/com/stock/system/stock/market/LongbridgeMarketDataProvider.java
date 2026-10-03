package com.stock.system.stock.market;

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

    LongbridgeMarketDataProvider(StockMapper stockMapper, LongbridgeQuoteGateway quoteGateway) {
        this.stockMapper = stockMapper;
        this.quoteGateway = quoteGateway;
    }

    @Override
    public List<StockQuoteSnapshot> search(String keyword) {
        List<Stock> stocks = stockMapper.searchDirectory(keyword);
        if (stocks.isEmpty()) {
            return List.of();
        }
        Map<String, LongbridgeQuote> quotes;
        try {
            quotes = quoteGateway.getQuotes(stocks.stream().map(Stock::getCode).toList());
        } catch (LongbridgeUpstreamException exception) {
            LOGGER.warn("Longbridge quotes are unavailable during stock search: {}", exception.getMessage());
            quotes = Map.of();
        }
        Map<String, LongbridgeQuote> availableQuotes = quotes;
        return stocks.stream()
                .map(stock -> toSnapshot(stock, availableQuotes.get(stock.getCode())))
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
        try {
            LongbridgeQuote quote = quoteGateway.getQuotes(List.of(normalizedCode)).get(normalizedCode);
            if (!isUsable(quote)) {
                return Optional.empty();
            }
            return Optional.of(toSnapshot(stock, quote));
        } catch (LongbridgeUpstreamException exception) {
            LOGGER.warn("Longbridge quote is unavailable for {}: {}", normalizedCode, exception.getMessage());
            return Optional.empty();
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
                usable ? mapListingStatus(quote.tradeStatus()) : "UNKNOWN",
                usable ? quote.lastDone() : null,
                quoteTime,
                usable ? SOURCE : stock.getSource(),
                false
        );
    }

    private boolean isUsable(LongbridgeQuote quote) {
        return quote != null
                && quote.lastDone() != null
                && quote.lastDone().signum() > 0
                && quote.timestamp() != null;
    }

    private String mapListingStatus(String tradeStatus) {
        if (tradeStatus == null) {
            return "UNKNOWN";
        }
        return switch (tradeStatus.trim().toUpperCase(Locale.ROOT)) {
            case "NORMAL", "0" -> "LISTED";
            case "HALTED", "1" -> "SUSPENDED";
            case "DELISTED", "2" -> "DELISTED";
            default -> "UNKNOWN";
        };
    }
}
