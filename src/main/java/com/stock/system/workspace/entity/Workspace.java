package com.stock.system.workspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workspace")
public class Workspace {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private Long createdByUserId;

    private Long ownerUserId;

    private String status;

    private LocalDateTime archivedAt;

    private LocalDateTime createdAt;
}
