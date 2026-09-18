package com.stock.system.stock.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EndedWorkspaceStockRow {
    private Long workspaceStockId;
    private String code;
    private String name;
    private String exchange;
    private String currency;
    private Long researchCycleId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
