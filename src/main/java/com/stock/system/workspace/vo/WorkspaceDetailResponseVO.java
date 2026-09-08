package com.stock.system.workspace.vo;

import java.time.LocalDateTime;

public record WorkspaceDetailResponseVO(
        Long workspaceId,
        String name,
        String role,
        String status,
        long memberCount,
        Long createdByUserId,
        Long ownerUserId,
        LocalDateTime archivedAt,
        LocalDateTime createdAt
) {
}
