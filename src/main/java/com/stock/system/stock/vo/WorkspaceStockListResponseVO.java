package com.stock.system.stock.vo;

import com.stock.system.stock.market.StockStatusInfo;

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
        Long latestOpinionId,
        String latestOpinionType,
        Long latestOpinionAuthorUserId,
        String latestOpinionAuthorNickname,
        String latestOpinionStance,
        LocalDateTime latestOpinionCreatedAt,
        int opinionCount,
        int participantCount,
        StockStatusInfo statusInfo
) {
}
