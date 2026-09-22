package com.stock.system.admin.vo;

import java.time.LocalDateTime;
import java.util.List;

public record AdminUserDetailResponseVO(
        Long userId,
        String username,
        String nickname,
        String status,
        String accountType,
        long workspaceCount,
        long ownedWorkspaceCount,
        LocalDateTime createdAt,
        List<AdminMembershipResponseVO> memberships
) {
}
