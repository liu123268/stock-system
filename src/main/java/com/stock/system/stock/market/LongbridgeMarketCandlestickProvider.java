package com.stock.system.stock.market;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "longbridge")
class LongbridgeMarketCandlestickProvider implements MarketCandlestickProvider {

    private static final String SOURCE = "LONGBRIDGE_OPENAPI";

    private final LongbridgeCandlestickGateway gateway;

    LongbridgeMarketCandlestickProvider(LongbridgeCandlestickGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public MarketCandlestickSeries getCandlesticks(
            String code,
            CandlestickPeriod period,
            int count
    ) {
        try {
            var items = gateway.getCandlesticks(code, period, count).stream()
                    .map(item -> new MarketCandlestick(
                            item.timestamp(), item.open(), item.high(), item.low(), item.close(),
                            item.volume(), item.turnover()))
                    .toList();
            if (items.isEmpty()) {
                throw new MarketCandlestickUnavailableException("Longbridge returned no candlesticks");
            }
            return new MarketCandlestickSeries(
                    items, SOURCE, false, Instant.now(), items.size() == count);
        } catch (LongbridgeCliException exception) {
            throw translate(exception);
        }
    }

    @Override
    public MarketCandlestickSeries getCandlesticksBefore(
            String code,
            CandlestickPeriod period,
            int count,
            Instant before
    ) {
        try {
            List<LongbridgeCandlestick> raw = gateway.getCandlesticksBefore(
                    code, period, count, before);
            var items = raw.stream()
                    .filter(item -> item.timestamp().isBefore(before))
                    .sorted(Comparator.comparing(LongbridgeCandlestick::timestamp))
                    .map(item -> new MarketCandlestick(
                            item.timestamp(), item.open(), item.high(), item.low(), item.close(),
                            item.volume(), item.turnover()))
                    .toList();
            if (items.isEmpty()) {
                return new MarketCandlestickSeries(
                        List.of(), SOURCE, false, Instant.now(), false);
            }
            return new MarketCandlestickSeries(
                    items, SOURCE, false, Instant.now(), raw.size() == count);
        } catch (LongbridgeCliException exception) {
            throw translate(exception);
        }
    }

    private RuntimeException translate(LongbridgeCliException exception) {
        if (exception.hasProviderCode("301607")) {
            return new MarketCandlestickLimitExceededException(exception);
        }
        return new MarketCandlestickUnavailableException(
                "Longbridge candlesticks are unavailable", exception);
    }
}
