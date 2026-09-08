package com.stock.system.workspace.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWorkspaceRequestDTO(
        @NotBlank(message = "空间名称不能为空")
        String name
) {
}
