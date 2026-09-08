package com.stock.system.stock.mapper;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 共享自选列表的数据库联表投影，只在 Mapper 与 Service 之间传递。
 */
@Data
public class WorkspaceStockListRow {

    private Long workspaceStockId;
    private Long researchCycleId;
    private String code;
    private String name;
    private String exchange;
    private String currency;
    private String listingStatus;
    private String attentionLevel;
    private Long startedByUserId;
    private String startedByNickname;
    private LocalDateTime startedAt;
    private BigDecimal startPrice;
    private LocalDateTime quoteTime;
    private String quoteSource;
    private Boolean simulated;
    private Long initialOpinionId;
    private Long authorUserId;
    private String authorNickname;
    private String stance;
    private String reason;
    private String riskNote;
    private String horizon;
    private BigDecimal targetPrice;
    private BigDecimal stopLossPrice;
    private LocalDateTime opinionCreatedAt;
}
