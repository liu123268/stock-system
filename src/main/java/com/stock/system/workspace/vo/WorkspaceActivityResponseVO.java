package com.stock.system.workspace.vo;

import java.time.LocalDateTime;

public record WorkspaceActivityResponseVO(
        String eventType,
        Long workspaceStockId,
        String stockCode,
        String stockName,
        String actorNickname,
        LocalDateTime occurredAt,
        String opinionType,
        String stance,
        String attentionLevel,
        boolean opinionWithdrawn
) {
}
