package com.stock.system.stock.market;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

final class MarketCacheTtl {

    private MarketCacheTtl() {
    }

    static Duration withJitter(Duration base, int percent) {
        if (base == null || base.isZero() || base.isNegative()) {
            throw new IllegalArgumentException("Market cache TTL must be positive");
        }
        if (percent < 0 || percent > 50) {
            throw new IllegalArgumentException("Market cache TTL jitter must be between 0 and 50 percent");
        }
        long baseMillis = Math.max(1, base.toMillis());
        long spread = baseMillis * percent / 100;
        if (spread == 0) {
            return Duration.ofMillis(baseMillis);
        }
        return Duration.ofMillis(baseMillis + ThreadLocalRandom.current()
                .nextLong(-spread, spread + 1));
    }
}
