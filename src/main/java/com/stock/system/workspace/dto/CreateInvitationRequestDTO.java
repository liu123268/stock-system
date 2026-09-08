package com.stock.system.workspace.dto;

import jakarta.validation.constraints.Positive;

public record CreateInvitationRequestDTO(
        @Positive(message = "当前邀请码 ID 必须是正数") Long currentInvitationId
) {
}
