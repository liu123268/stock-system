package com.stock.system.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequestDTO(
        @NotBlank(message = "原密码不能为空") String currentPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 4, max = 64, message = "新密码长度必须在4到64个字符之间")
        String newPassword
) {
}
