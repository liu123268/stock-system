package com.stock.system.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求 DTO：只接收登录所需的账号和原始密码。
 */
public record LoginRequestDTO(
        @NotBlank(message = "登录账号不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        // 登录验证已有凭据，保留旧四位密码；新密码长度由注册和改密 DTO 约束。
        @Size(min = 4, max = 64, message = "密码长度必须在4到64个字符之间")
        String password
) {
}
