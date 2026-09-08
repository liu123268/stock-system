package com.stock.system.workspace.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TransferOwnershipRequestDTO(
        @NotNull(message = "请选择要转让给的成员")
        @Positive(message = "目标用户 ID 必须是正数")
        Long targetUserId
) {
}
