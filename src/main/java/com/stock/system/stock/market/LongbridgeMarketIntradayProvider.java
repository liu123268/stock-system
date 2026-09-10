package com.stock.system.stock.market;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "longbridge")
class LongbridgeMarketIntradayProvider implements MarketIntradayProvider {

    private static final String SOURCE = "LONGBRIDGE_OPENAPI";

    private final LongbridgeIntradayGateway intradayGateway;
    private final LongbridgeCandlestickGateway candlestickGateway;

    LongbridgeMarketIntradayProvider(
            LongbridgeIntradayGateway intradayGateway,
            LongbridgeCandlestickGateway candlestickGateway
    ) {
        this.intradayGateway = intradayGateway;
        this.candlestickGateway = candlestickGateway;
    }

    @Override
    public MarketIntradaySeries getIntraday(String code, LocalDate tradeDate) {
        ZoneId zone = marketZone(code);
        LocalDate marketToday = LocalDate.now(zone);
        if (tradeDate.isAfter(marketToday)) {
            throw new MarketIntradayUnavailableException("Future intraday data is unavailable");
        }
        try {
            List<MarketIntradayPoint> points = tradeDate.equals(marketToday)
                    ? currentPoints(code)
                    : historicalPoints(code, tradeDate);
            if (points.isEmpty()) {
                throw new MarketIntradayUnavailableException("Longbridge returned no intraday data");
            }
            return new MarketIntradaySeries(
                    tradeDate,
                    zone.getId(),
                    MarketTradingSessions.forSymbol(code),
                    previousClose(code, tradeDate, zone),
                    isUpdating(tradeDate, zone),
                    points,
                    SOURCE,
                    false,
                    Instant.now()
            );
        } catch (LongbridgeCliException exception) {
            if (exception.hasProviderCode("301607")) {
                throw new MarketIntradayLimitExceededException(exception);
            }
            throw new MarketIntradayUnavailableException(
                    "Longbridge intraday data is unavailable", exception);
        }
    }

    private List<MarketIntradayPoint> currentPoints(String code) {
        return intradayGateway.getIntraday(code).stream()
                .sorted(Comparator.comparing(LongbridgeIntradayPoint::timestamp))
                .map(item -> new MarketIntradayPoint(
                        item.timestamp(), item.price(), item.averagePrice(), item.volume(),
                        item.turnover()))
                .toList();
    }

    private List<MarketIntradayPoint> historicalPoints(String code, LocalDate tradeDate) {
        ZoneId marketZone = MarketTradingSessions.zoneFor(code);
        List<LongbridgeCandlestick> bars = candlestickGateway.getCandlesticksOnDate(
                code, CandlestickPeriod.MINUTE_1, tradeDate).stream()
                .filter(item -> item.timestamp().atZone(marketZone).toLocalDate().equals(tradeDate))
                .sorted(Comparator.comparing(LongbridgeCandlestick::timestamp))
                .toList();
        List<MarketIntradayPoint> points = new ArrayList<>(bars.size());
        BigDecimal cumulativeQuantity = BigDecimal.ZERO;
        BigDecimal cumulativeTurnover = BigDecimal.ZERO;
        BigDecimal lotSize = chinaALotSize(code);
        for (LongbridgeCandlestick bar : bars) {
            cumulativeQuantity = cumulativeQuantity.add(
                    BigDecimal.valueOf(bar.volume()).multiply(lotSize));
            cumulativeTurnover = cumulativeTurnover.add(bar.turnover());
            BigDecimal averagePrice = cumulativeQuantity.signum() == 0
                    ? bar.close()
                    : cumulativeTurnover.divide(
                            cumulativeQuantity, 4, java.math.RoundingMode.HALF_UP);
            points.add(new MarketIntradayPoint(
                    bar.timestamp(), bar.close(), averagePrice, bar.volume(), bar.turnover()));
        }
        return List.copyOf(points);
    }

    private BigDecimal chinaALotSize(String symbol) {
        String normalized = symbol.toUpperCase();
        return normalized.endsWith(".SH") || normalized.endsWith(".SZ")
                || normalized.endsWith(".BJ")
                ? BigDecimal.valueOf(100)
                : BigDecimal.ONE;
    }

    private BigDecimal previousClose(String code, LocalDate tradeDate, ZoneId zone) {
        Instant before = tradeDate.atStartOfDay(zone).toInstant();
        return candlestickGateway.getCandlesticksBefore(
                        code, CandlestickPeriod.DAY, 10, before).stream()
                .filter(item -> item.timestamp().atZone(zone).toLocalDate().isBefore(tradeDate))
                .max(Comparator.comparing(LongbridgeCandlestick::timestamp))
                .map(LongbridgeCandlestick::close)
                .filter(price -> price.signum() > 0)
                .orElseThrow(() -> new MarketIntradayUnavailableException(
                        "Previous close is unavailable for intraday percentage"));
    }

    private ZoneId marketZone(String symbol) {
        return MarketTradingSessions.zoneFor(symbol);
    }

    private boolean isUpdating(LocalDate tradeDate, ZoneId zone) {
        var now = java.time.ZonedDateTime.now(zone);
        if (!tradeDate.equals(now.toLocalDate())
                || now.getDayOfWeek() == DayOfWeek.SATURDAY
                || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        if (zone.getId().equals("Asia/Hong_Kong")) {
            return (!time.isBefore(LocalTime.of(9, 30)) && time.isBefore(LocalTime.NOON))
                    || (!time.isBefore(LocalTime.of(13, 0)) && time.isBefore(LocalTime.of(16, 5)));
        }
        return (!time.isBefore(LocalTime.of(9, 30)) && time.isBefore(LocalTime.of(11, 30)))
                || (!time.isBefore(LocalTime.of(13, 0)) && time.isBefore(LocalTime.of(15, 5)));
    }
}
