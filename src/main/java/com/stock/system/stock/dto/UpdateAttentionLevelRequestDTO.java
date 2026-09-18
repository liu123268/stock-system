package com.stock.system.stock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateAttentionLevelRequestDTO(
        @NotBlank(message = "关注级别不能为空")
        @Pattern(regexp = "NORMAL|FOCUS", message = "关注级别不合法")
        String attentionLevel
) {
}
