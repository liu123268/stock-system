package com.stock.system.stock.vo;

import com.stock.system.stock.dto.ResearchSourceFilter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 选定周期内的授权资料；超限时只返回范围信息，不返回截断正文。 */
public record ResearchCycleSourcesResponseVO(
        Long workspaceStockId,
        String stockCode,
        String stockName,
        Long researchCycleId,
        String cycleStatus,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Scope scope,
        List<OpinionSource> opinions
) {
    public enum Coverage { COMPLETE, LIMIT_EXCEEDED }

    public record Scope(
            LocalDateTime readAt,
            long validOpinionCount,
            long textCodePointCount,
            int readOpinionCount,
            Coverage coverage,
            int maxOpinionCount,
            int maxTextCodePoints,
            ResearchSourceFilter filter
    ) {
        public Scope(LocalDateTime readAt, long validOpinionCount, long textCodePointCount,
                     int readOpinionCount, Coverage coverage, int maxOpinionCount, int maxTextCodePoints) {
            this(readAt, validOpinionCount, textCodePointCount, readOpinionCount, coverage,
                    maxOpinionCount, maxTextCodePoints, ResearchSourceFilter.fullCycle());
        }
    }

    /** 原文查看及后续引用映射使用；不是整个业务实体或最终模型请求。 */
    public record OpinionSource(
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
            LocalDateTime createdAt
    ) {}
}
