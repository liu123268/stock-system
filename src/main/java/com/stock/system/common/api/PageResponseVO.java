package com.stock.system.common.api;

import java.util.List;

public record PageResponseVO<T>(
        List<T> items,
        int page,
        int size,
        long total,
        int totalPages
) {
    public static <T> PageResponseVO<T> of(List<T> items, int page, int size, long total) {
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponseVO<>(items, page, size, total, totalPages);
    }
}
