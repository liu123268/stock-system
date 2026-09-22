package com.stock.system.admin.vo;

import java.time.LocalDateTime;

public record AdminWorkspaceSummaryResponseVO(
        Long workspaceId,
        String name,
        String status,
        Long createdByUserId,
        String createdByUsername,
        Long ownerUserId,
        String ownerUsername,
        long memberCount,
        LocalDateTime archivedAt,
        LocalDateTime createdAt
) {
}
