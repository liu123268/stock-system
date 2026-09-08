package com.stock.system.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("super_admin_operation_log")
public class SuperAdminOperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String action;

    private Long targetId;

    private String beforeStatus;

    private String afterStatus;

    private String result;

    private LocalDateTime createdAt;
}
