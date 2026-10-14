package com.stock.system.stock.financial;

import java.time.Instant;

/** Internal read-only transport. No URL, arbitrary endpoint or account data is accepted. */
public interface FinancialSourceGateway {
    SourceReport readFinancialReport(String symbol, FinancialPeriod period);

    record SourceReport(String json, Instant retrievedAt, String source) { }

    enum FailureReason { TIMEOUT, RATE_LIMIT, ACCESS_DENIED, SOURCE_FAILURE, UNEXPECTED_RESPONSE }

    final class SourceFailure extends RuntimeException {
        private final FailureReason reason;
        public SourceFailure(FailureReason reason) {
            super("Financial source unavailable: " + reason.name());
            this.reason = reason;
        }
        public FailureReason reason() { return reason; }
    }

    static void validateSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[0-9]{6}\\.(?:SH|SZ)|[0-9]{1,5}\\.HK")) {
            throw new IllegalArgumentException("Unsupported financial symbol");
        }
    }
}
