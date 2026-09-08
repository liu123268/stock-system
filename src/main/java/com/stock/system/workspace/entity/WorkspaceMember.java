package com.stock.system.workspace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workspace_member")
public class WorkspaceMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workspaceId;

    private Long userId;

    private String status;

    private LocalDateTime firstJoinedAt;

    private LocalDateTime lastJoinedAt;

    private LocalDateTime lastInactiveAt;
}
