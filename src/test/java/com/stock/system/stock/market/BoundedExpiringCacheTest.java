package com.stock.system.stock.market;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedExpiringCacheTest {

    @Test
    void shouldRemoveExpiredEntriesWhenRead() {
        BoundedExpiringCache<String, String> cache = new BoundedExpiringCache<>(2);
        Instant now = Instant.parse("2026-09-13T00:00:00Z");

        cache.put("expired", "old", now.minusSeconds(1));

        assertThat(cache.getIfFresh("expired", now)).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    void shouldEvictLeastRecentlyUsedEntryAtCapacity() {
        BoundedExpiringCache<String, String> cache = new BoundedExpiringCache<>(2);
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        Instant later = now.plusSeconds(30);

        cache.put("first", "A", later);
        cache.put("second", "B", later);
        assertThat(cache.getIfFresh("first", now)).isEqualTo("A");
        cache.put("third", "C", later);

        assertThat(cache.getIfFresh("second", now)).isNull();
        assertThat(cache.getIfFresh("first", now)).isEqualTo("A");
        assertThat(cache.getIfFresh("third", now)).isEqualTo("C");
        assertThat(cache.size()).isEqualTo(2);
    }
}
