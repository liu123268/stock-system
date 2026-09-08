package com.stock.system.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("research_cycle")
public class ResearchCycle {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workspaceStockId;
    private String status;
    private String attentionLevel;
    private Long startedByUserId;
    private LocalDateTime startedAt;
    private BigDecimal startPrice;
    private LocalDateTime quoteTime;
    private String quoteSource;
    private Boolean isSimulated;
    private LocalDateTime endedAt;
}
