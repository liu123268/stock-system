package com.stock.system.stock.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record WorkspaceStockListResponseVO(
        Long workspaceStockId,
        Long researchCycleId,
        String code,
        String name,
        String exchange,
        String currency,
        String listingStatus,
        String attentionLevel,
        Long startedByUserId,
        String startedByNickname,
        LocalDateTime startedAt,
        BigDecimal startPrice,
        LocalDateTime quoteTime,
        String quoteSource,
        boolean simulated,
        Long initialOpinionId,
        Long authorUserId,
        String authorNickname,
        String stance,
        String reason,
        String observationNote,
        String horizon,
        BigDecimal targetPrice,
        BigDecimal stopLossPrice,
        LocalDateTime opinionCreatedAt,
        boolean initialOpinionWithdrawn
) {
}
