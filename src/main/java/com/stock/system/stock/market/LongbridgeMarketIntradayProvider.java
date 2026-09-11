package com.stock.system.stock.market;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
    private final LongbridgeTradingDayGateway tradingDayGateway;
    private final Clock clock;

    @Autowired
    LongbridgeMarketIntradayProvider(
            LongbridgeIntradayGateway intradayGateway,
            LongbridgeCandlestickGateway candlestickGateway,
            LongbridgeTradingDayGateway tradingDayGateway
    ) {
        this(intradayGateway, candlestickGateway, tradingDayGateway, Clock.systemUTC());
    }

    LongbridgeMarketIntradayProvider(
            LongbridgeIntradayGateway intradayGateway,
            LongbridgeCandlestickGateway candlestickGateway,
            LongbridgeTradingDayGateway tradingDayGateway,
            Clock clock
    ) {
        this.intradayGateway = intradayGateway;
        this.candlestickGateway = candlestickGateway;
        this.tradingDayGateway = tradingDayGateway;
        this.clock = clock;
    }

    @Override
    public MarketIntradaySeries getIntraday(String code, LocalDate tradeDate) {
        ZoneId zone = marketZone(code);
        LocalDate marketToday = LocalDate.now(clock.withZone(zone));
        if (tradeDate.isAfter(marketToday)) {
            throw new MarketIntradayUnavailableException("Future intraday data is unavailable");
        }
        try {
            boolean tradingDay = validateTradingDay(code, tradeDate, marketToday);
            LoadedIntraday loaded = tradeDate.equals(marketToday)
                    ? currentPoints(code, tradeDate, zone)
                    : historicalPoints(code, tradeDate);
            List<MarketIntradayPoint> points = loaded.points();
            if (points.isEmpty()) {
                throw new MarketIntradayUnavailableException("Longbridge returned no intraday data");
            }
            return new MarketIntradaySeries(
                    tradeDate,
                    zone.getId(),
                    MarketTradingSessions.forSymbol(code),
                    previousClose(code, tradeDate, zone),
                    isUpdating(tradeDate, zone, tradingDay),
                    points,
                    SOURCE,
                    false,
                    loaded.retrievedAt()
            );
        } catch (LongbridgeCliException exception) {
            var earliestDate = exception.minuteHistoryBeginDate();
            if (earliestDate.isPresent()) {
                throw new MarketIntradayHistoryRangeExceededException(
                        earliestDate.get(), exception);
            }
            if (exception.hasProviderCode("301607")) {
                throw new MarketIntradayLimitExceededException(exception);
            }
            throw new MarketIntradayUnavailableException(
                    "Longbridge intraday data is unavailable", exception);
        }
    }

    private boolean validateTradingDay(String code, LocalDate tradeDate, LocalDate marketToday) {
        if (tradeDate.isBefore(marketToday.minusYears(1))) {
            return true;
        }
        if (!tradingDayGateway.isTradingDay(code, tradeDate)) {
            throw new MarketIntradayNonTradingDayException();
        }
        return true;
    }

    private LoadedIntraday currentPoints(
            String code,
            LocalDate tradeDate,
            ZoneId zone
    ) {
        List<LongbridgeIntradayPoint> raw = intradayGateway.getIntraday(code);
        List<MarketIntradayPoint> points = raw.stream()
                .filter(item -> item.timestamp().atZone(zone).toLocalDate().equals(tradeDate))
                .sorted(Comparator.comparing(LongbridgeIntradayPoint::timestamp))
                .map(item -> new MarketIntradayPoint(
                        item.timestamp(), item.price(), item.averagePrice(), item.volume(),
                        item.turnover()))
                .toList();
        return new LoadedIntraday(points, raw.stream()
                .map(LongbridgeIntradayPoint::retrievedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElseGet(clock::instant));
    }

    private LoadedIntraday historicalPoints(String code, LocalDate tradeDate) {
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
        Instant retrievedAt = bars.stream()
                .map(LongbridgeCandlestick::retrievedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElseGet(clock::instant);
        return new LoadedIntraday(List.copyOf(points), retrievedAt);
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

    private boolean isUpdating(LocalDate tradeDate, ZoneId zone, boolean tradingDay) {
        var now = java.time.ZonedDateTime.now(clock.withZone(zone));
        if (!tradingDay || !tradeDate.equals(now.toLocalDate())) {
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

    private record LoadedIntraday(List<MarketIntradayPoint> points, Instant retrievedAt) {
    }
}
