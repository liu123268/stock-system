package com.stock.system.stock.market;

import java.time.LocalDate;

interface LongbridgeTradingDayGateway {

    boolean isTradingDay(String symbol, LocalDate date);
}
