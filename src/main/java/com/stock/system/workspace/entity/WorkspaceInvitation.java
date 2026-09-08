package com.stock.system.workspace.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workspace_invitation")
public class WorkspaceInvitation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    private String inviteCode;

    private Long createdByUserId;

    private LocalDateTime expiresAt;

    private Integer usedCount;

    private String status;

    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long activeWorkspaceId;
}
