package com.stock.system.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("stock_opinion")
public class StockOpinion {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long researchCycleId;
    private Long authorUserId;
    private String type;
    private String stance;
    private String reason;
    private String riskNote;
    private String horizon;
    private BigDecimal targetPrice;
    private BigDecimal stopLossPrice;
    private LocalDateTime createdAt;
}
