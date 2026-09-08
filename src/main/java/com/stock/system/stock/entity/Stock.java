package com.stock.system.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("stock")
public class Stock {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String exchange;
    private String currency;
    private String listingStatus;
    private String source;
    private String searchAliases;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
