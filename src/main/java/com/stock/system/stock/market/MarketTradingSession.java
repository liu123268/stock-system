package com.stock.system.stock.market;

import java.time.LocalTime;

public record MarketTradingSession(LocalTime start, LocalTime end) {
}
