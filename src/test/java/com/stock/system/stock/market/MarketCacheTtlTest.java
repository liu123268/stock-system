package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketCacheTtlTest {

    @Test
    void jitterKeepsTtlInsideConfiguredRange() {
        for (int sample = 0; sample < 100; sample++) {
            Duration ttl = MarketCacheTtl.withJitter(Duration.ofSeconds(10), 10);
            assertThat(ttl).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(11));
        }
    }

    @Test
    void rejectsInvalidTtlConfiguration() {
        assertThatThrownBy(() -> MarketCacheTtl.withJitter(Duration.ZERO, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketCacheTtl.withJitter(Duration.ofSeconds(5), 51))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
