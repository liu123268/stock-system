package com.stock.system.stock.market;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class LongbridgeCliException extends RuntimeException {

    private static final Pattern MINUTE_HISTORY_BEGIN_DATE = Pattern.compile(
            "out of minute candlestick limit begin date:(\\d{4}-\\d{2}-\\d{2})",
            Pattern.CASE_INSENSITIVE
    );

    private final String providerCode;
    private final String providerMessage;

    LongbridgeCliException(String message) {
        this(null, message, null, null);
    }

    LongbridgeCliException(String message, Throwable cause) {
        this(null, message, null, cause);
    }

    LongbridgeCliException(String providerCode, String message) {
        this(providerCode, message, null, null);
    }

    LongbridgeCliException(String providerCode, String message, String providerMessage) {
        this(providerCode, message, providerMessage, null);
    }

    private LongbridgeCliException(
            String providerCode,
            String message,
            String providerMessage,
            Throwable cause
    ) {
        super(message, cause);
        this.providerCode = providerCode;
        this.providerMessage = providerMessage;
    }

    boolean hasProviderCode(String expected) {
        return expected.equals(providerCode);
    }

    Optional<LocalDate> minuteHistoryBeginDate() {
        if (providerMessage == null) {
            return Optional.empty();
        }
        Matcher matcher = MINUTE_HISTORY_BEGIN_DATE.matcher(providerMessage);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(matcher.group(1)));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }
}
