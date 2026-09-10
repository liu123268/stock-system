package com.stock.system.stock.vo;

import java.time.LocalTime;

public record StockTradingSessionVO(LocalTime start, LocalTime end) {
}
