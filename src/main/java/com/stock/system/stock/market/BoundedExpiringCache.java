package com.stock.system.stock.market;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class BoundedExpiringCache<K, V> {

    private final int maximumSize;
    private final Map<K, Entry<V>> entries;

    BoundedExpiringCache(int maximumSize) {
        if (maximumSize < 1) {
            throw new IllegalArgumentException("Cache maximum size must be positive");
        }
        this.maximumSize = maximumSize;
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    synchronized V getIfFresh(K key, Instant now) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (!entry.expiresAt().isAfter(now)) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    synchronized void put(K key, V value, Instant expiresAt) {
        entries.put(key, new Entry<>(value, expiresAt));
        while (entries.size() > maximumSize) {
            K leastRecentlyUsedKey = entries.keySet().iterator().next();
            entries.remove(leastRecentlyUsedKey);
        }
    }

    synchronized int size() {
        return entries.size();
    }

    private record Entry<V>(V value, Instant expiresAt) {
    }
}
