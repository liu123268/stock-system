package com.stock.system.workspace.vo;

import java.time.LocalDateTime;

public record InvitationHistoryResponseVO(
        Long invitationId,
        String status,
        int usedCount,
        int remainingUses,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
}
