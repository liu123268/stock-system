package com.stock.system.workspace.vo;

import java.time.LocalDateTime;

public record WorkspaceMemberResponseVO(
        Long userId,
        String username,
        String nickname,
        String role,
        String status,
        LocalDateTime firstJoinedAt,
        LocalDateTime lastJoinedAt
) {
}
