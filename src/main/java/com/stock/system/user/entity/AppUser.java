package com.stock.system.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据库实体：一一对应 app_user 表。
 */
@Data
@TableName("app_user")
public class AppUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    private Long credentialVersion;

    private String nickname;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
