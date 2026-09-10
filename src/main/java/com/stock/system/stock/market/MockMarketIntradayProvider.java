package com.stock.system.stock.market;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "stock-system.market.mode", havingValue = "mock", matchIfMissing = true)
class MockMarketIntradayProvider implements MarketIntradayProvider {

    @Override
    public MarketIntradaySeries getIntraday(String code, LocalDate tradeDate) {
        ZoneId zone = MarketTradingSessions.zoneFor(code);
        List<MarketTradingSession> tradingSessions = MarketTradingSessions.forSymbol(code);
        List<MarketIntradayPoint> points = new ArrayList<>();
        BigDecimal turnover = BigDecimal.ZERO;
        long volume = 0;
        int index = 0;
        for (MarketTradingSession session : tradingSessions) {
            for (var time = session.start(); !time.isAfter(session.end()); time = time.plusMinutes(1)) {
                BigDecimal price = new BigDecimal("100.0000")
                        .add(BigDecimal.valueOf(Math.sin(index / 18.0)).multiply(new BigDecimal("1.8")))
                        .add(BigDecimal.valueOf(index).multiply(new BigDecimal("0.004")));
                long pointVolume = 1000L + index * 10L;
                BigDecimal pointTurnover = price.multiply(BigDecimal.valueOf(pointVolume));
                volume += pointVolume;
                turnover = turnover.add(pointTurnover);
                BigDecimal average = turnover.divide(
                        BigDecimal.valueOf(volume), 4, java.math.RoundingMode.HALF_UP);
                Instant timestamp = tradeDate.atTime(time).atZone(zone).toInstant();
                points.add(new MarketIntradayPoint(
                        timestamp, price, average, pointVolume, pointTurnover));
                index++;
            }
        }
        return new MarketIntradaySeries(
                tradeDate, zone.getId(), tradingSessions, new BigDecimal("100.0000"),
                tradeDate.equals(LocalDate.now(zone)), List.copyOf(points),
                "LOCAL_MOCK", true, Instant.now());
    }
}
