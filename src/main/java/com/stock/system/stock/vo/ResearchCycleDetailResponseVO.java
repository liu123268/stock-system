package com.stock.system.stock.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ResearchCycleDetailResponseVO(
        Long id,
        String status,
        String attentionLevel,
        Long startedByUserId,
        String startedByNickname,
        LocalDateTime startedAt,
        BigDecimal startPrice,
        LocalDateTime quoteTime,
        String quoteSource,
        boolean simulated,
        LocalDateTime endedAt,
        List<StockOpinionResponseVO> opinions
) {
}
