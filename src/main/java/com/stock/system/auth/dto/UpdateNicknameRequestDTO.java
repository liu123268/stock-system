package com.stock.system.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNicknameRequestDTO(
        @NotBlank(message = "昵称不能为空")
        @Size(max = 20, message = "昵称最多20个字符")
        String nickname
) {
}
