package com.stock.system.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求 DTO：只声明注册者被允许填写的内容。
 */
public record RegisterRequestDTO(
        @NotBlank(message = "登录账号不能为空")
        @Size(min = 2, max = 20, message = "登录账号长度必须在2到20个字符之间")
        @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "登录账号只能包含字母、数字和下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度必须在6到64个字符之间")
        String password,

        @NotBlank(message = "昵称不能为空")
        @Size(max = 20, message = "昵称最多20个字符")
        String nickname
) {
}
