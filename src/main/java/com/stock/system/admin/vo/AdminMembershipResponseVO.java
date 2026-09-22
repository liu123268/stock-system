package com.stock.system.admin.vo;

import java.time.LocalDateTime;

public record AdminMembershipResponseVO(
        Long workspaceId,
        String workspaceName,
        String workspaceStatus,
        String role,
        String membershipStatus,
        LocalDateTime firstJoinedAt,
        LocalDateTime lastJoinedAt,
        LocalDateTime lastInactiveAt
) {
}
