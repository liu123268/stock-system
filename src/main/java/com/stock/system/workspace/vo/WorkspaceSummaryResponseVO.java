package com.stock.system.workspace.vo;

import java.time.LocalDateTime;

public record WorkspaceSummaryResponseVO(
        Long workspaceId,
        String name,
        String role,
        String status,
        long memberCount,
        LocalDateTime createdAt
) {
}
