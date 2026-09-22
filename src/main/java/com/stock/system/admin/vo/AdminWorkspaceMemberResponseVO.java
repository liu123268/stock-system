package com.stock.system.admin.vo;

import java.time.LocalDateTime;

public record AdminWorkspaceMemberResponseVO(
        Long userId,
        String username,
        String nickname,
        String accountStatus,
        String role,
        String membershipStatus,
        LocalDateTime firstJoinedAt,
        LocalDateTime lastJoinedAt,
        LocalDateTime lastInactiveAt
) {
}
