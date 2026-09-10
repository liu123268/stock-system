package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MarketTradingSessionsTest {

    @Test
    void exposesOnlyCurrentProductMarkets() {
        assertThat(MarketTradingSessions.forSymbol("600519.SH")).containsExactly(
                new MarketTradingSession(LocalTime.of(9, 30), LocalTime.of(11, 30)),
                new MarketTradingSession(LocalTime.of(13, 0), LocalTime.of(15, 0))
        );
        assertThat(MarketTradingSessions.forSymbol("00700.HK")).containsExactly(
                new MarketTradingSession(LocalTime.of(9, 30), LocalTime.of(12, 0)),
                new MarketTradingSession(LocalTime.of(13, 0), LocalTime.of(16, 0))
        );
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MarketTradingSessions.forSymbol("AAPL.US"));
    }
}
