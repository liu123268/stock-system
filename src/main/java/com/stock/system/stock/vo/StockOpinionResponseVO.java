package com.stock.system.stock.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockOpinionResponseVO(
        Long id,
        Long authorUserId,
        String authorNickname,
        String type,
        String stance,
        String reason,
        String observationNote,
        String horizon,
        BigDecimal targetPrice,
        BigDecimal stopLossPrice,
        LocalDateTime createdAt,
        boolean withdrawn,
        LocalDateTime withdrawnAt,
        boolean canWithdraw
) {
}
