package com.stock.system.stock.market;

import java.util.Locale;

/** Accepts SDK enum names, CLI names and protocol numeric values. Never infers delisting. */
public final class LongbridgeTradeStatus {
    private LongbridgeTradeStatus() { }

    public static String normalize(String value) {
        if (value == null) return null;
        return switch (value.trim().replace("_", "").toUpperCase(Locale.ROOT)) {
            case "NORMAL", "0" -> "NORMAL";
            case "HALTED", "1" -> "HALTED";
            case "DELISTED", "2" -> "DELISTED";
            case "FUSE", "3" -> "FUSE";
            case "PREPARELIST", "4" -> "PREPARE_LIST";
            case "CODEMOVED", "5" -> "CODE_MOVED";
            case "TOBEOPENED", "6" -> "TO_BE_OPENED";
            case "SPLITSTOCKHALTS", "7" -> "SPLIT_STOCK_HALTS";
            case "EXPIRED", "8" -> "EXPIRED";
            case "WARRANTPREPARELIST", "9" -> "WARRANT_PREPARE_LIST";
            case "SUSPENDTRADE", "10" -> "SUSPEND_TRADE";
            default -> null;
        };
    }

    public static String listingStatus(String value) {
        String raw = normalize(value);
        if (raw == null) return "UNKNOWN";
        return switch (raw) {
            case "NORMAL" -> "LISTED";
            case "HALTED", "SUSPEND_TRADE" -> "SUSPENDED";
            case "DELISTED" -> "DELISTED";
            default -> "UNKNOWN";
        };
    }
}
