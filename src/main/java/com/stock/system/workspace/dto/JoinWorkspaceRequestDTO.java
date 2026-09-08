package com.stock.system.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record JoinWorkspaceRequestDTO(
        @NotBlank(message = "请输入邀请码")
        @Pattern(regexp = "^[A-Z0-9]{6}$", message = "邀请码必须是 6 位大写字母或数字")
        String inviteCode
) {
}
