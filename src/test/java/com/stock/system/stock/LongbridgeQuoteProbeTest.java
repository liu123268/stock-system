package com.stock.system.stock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in only: no Spring context, database, business API or trading calls. */
class LongbridgeQuoteProbeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "STOCK_LONGBRIDGE_PROBE", matches = "true")
    void pullsQuoteAfterInteractiveOAuth() {
        // Secrets never become Maven -D properties (Surefire records those in XML).
        LongbridgeLocalAuthorization.run();
    }
}
