package com.stock.system.admin.vo;

import java.time.LocalDateTime;

public record AdminUserSummaryResponseVO(
        Long userId,
        String username,
        String nickname,
        String status,
        String accountType,
        long workspaceCount,
        long ownedWorkspaceCount,
        LocalDateTime createdAt
) {
}
