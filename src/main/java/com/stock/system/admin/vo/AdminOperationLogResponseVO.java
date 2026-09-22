package com.stock.system.admin.vo;

import java.time.LocalDateTime;

public record AdminOperationLogResponseVO(
        Long operationLogId,
        String action,
        Long targetId,
        String targetLabel,
        String beforeStatus,
        String afterStatus,
        String result,
        LocalDateTime createdAt
) {
}
