package com.stock.system.stock.market;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

final class MarketCacheKeys {

    private MarketCacheKeys() {
    }

    static String quotes(String prefix, List<String> symbols) {
        String canonicalSymbols = symbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .map(MarketCacheKeys::normalizeSymbol)
                .distinct()
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return prefix(prefix) + ":quote:" + sha256(canonicalSymbols);
    }

    static String latestCandlesticks(
            String prefix,
            String symbol,
            CandlestickPeriod period,
            int count
    ) {
        return prefix(prefix) + ":candlestick:" + normalizeSymbol(symbol)
                + ":" + period.apiValue() + ":latest:" + count;
    }

    static String candlesticksBefore(
            String prefix,
            String symbol,
            CandlestickPeriod period,
            int count,
            Instant before
    ) {
        return prefix(prefix) + ":candlestick:" + normalizeSymbol(symbol)
                + ":" + period.apiValue() + ":before:" + before.toEpochMilli() + ":" + count;
    }

    static String candlesticksOnDate(
            String prefix,
            String symbol,
            CandlestickPeriod period,
            LocalDate date
    ) {
        return prefix(prefix) + ":candlestick:" + normalizeSymbol(symbol)
                + ":" + period.apiValue() + ":date:" + date;
    }

    static String intraday(String prefix, String symbol, LocalDate marketDate) {
        return prefix(prefix) + ":intraday:" + normalizeSymbol(symbol) + ":" + marketDate;
    }

    static String tradingDay(String prefix, String symbol, LocalDate date) {
        return prefix(prefix) + ":trading-day:" + market(symbol) + ":" + date;
    }

    private static String prefix(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Market Redis key prefix cannot be blank");
        }
        return normalized;
    }

    private static String normalizeSymbol(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (normalized.isEmpty() || !normalized.matches("[A-Z0-9]+\\.[A-Z]+")) {
            throw new IllegalArgumentException("Invalid market cache symbol");
        }
        return normalized;
    }

    private static String market(String symbol) {
        String normalized = normalizeSymbol(symbol);
        if (normalized.endsWith(".HK")) {
            return "HK";
        }
        if (normalized.endsWith(".SH") || normalized.endsWith(".SZ")
                || normalized.endsWith(".BJ")) {
            return "CN";
        }
        throw new IllegalArgumentException("Unsupported market cache symbol");
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
