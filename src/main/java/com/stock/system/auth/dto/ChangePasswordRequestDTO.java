package com.stock.system.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record ChangePasswordRequestDTO(
        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank(message = "原密码不能为空") String currentPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "新密码长度必须在6到64个字符之间")
        @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        String newPassword
) {
}
