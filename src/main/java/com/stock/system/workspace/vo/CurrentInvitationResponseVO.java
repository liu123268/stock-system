package com.stock.system.workspace.vo;

import java.time.LocalDateTime;

public record CurrentInvitationResponseVO(
        Long invitationId,
        String inviteCode,
        String status,
        int usedCount,
        int remainingUses,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
