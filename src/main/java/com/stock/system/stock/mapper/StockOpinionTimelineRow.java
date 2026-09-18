package com.stock.system.stock.mapper;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class StockOpinionTimelineRow {
    private Long id;
    private Long authorUserId;
    private String authorNickname;
    private String type;
    private String stance;
    private String reason;
    private String observationNote;
    private String horizon;
    private BigDecimal targetPrice;
    private BigDecimal stopLossPrice;
    private LocalDateTime createdAt;
    private LocalDateTime withdrawnAt;
}
