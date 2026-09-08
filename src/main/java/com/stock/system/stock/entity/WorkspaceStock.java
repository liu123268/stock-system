package com.stock.system.stock.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workspace_stock")
public class WorkspaceStock {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workspaceId;
    private Long stockId;
    private Long firstAddedByUserId;
    private LocalDateTime firstAddedAt;
}
