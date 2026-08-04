package com.stock.stocksystem.entity;

import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("stock_record")
public class StockRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String stockCode;

    private String stockName;

    private BigDecimal recordedPrice;

    private String note;

    private String department;

    private Long createUserId;

    private LocalDateTime createTime;
}