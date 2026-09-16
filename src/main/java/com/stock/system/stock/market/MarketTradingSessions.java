package com.stock.system.stock.market;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

public final class MarketTradingSessions {

    private MarketTradingSessions() {
    }

    public static ZoneId zoneFor(String symbol) {
        String normalized = symbol.toUpperCase();
        if (normalized.endsWith(".HK")) return ZoneId.of("Asia/Hong_Kong");
        if (isChinaA(normalized)) return ZoneId.of("Asia/Shanghai");
        // V1 只定义了 A 股和港股交易时段；不能借用 sidecar 中的 .US 时区分支伪造美股交易规则。
        throw new IllegalArgumentException("Unsupported market symbol: " + symbol);
    }

    public static List<MarketTradingSession> forSymbol(String symbol) {
        String normalized = symbol.toUpperCase();
        if (normalized.endsWith(".HK")) {
            return List.of(session(9, 30, 12, 0), session(13, 0, 16, 0));
        }
        if (isChinaA(normalized)) {
            return List.of(session(9, 30, 11, 30), session(13, 0, 15, 0));
        }
        throw new IllegalArgumentException("Unsupported market symbol: " + symbol);
    }

    private static boolean isChinaA(String symbol) {
        return symbol.endsWith(".SH") || symbol.endsWith(".SZ") || symbol.endsWith(".BJ");
    }

    private static MarketTradingSession session(
            int startHour,
            int startMinute,
            int endHour,
            int endMinute
    ) {
        return new MarketTradingSession(
                LocalTime.of(startHour, startMinute),
                LocalTime.of(endHour, endMinute));
    }
}
