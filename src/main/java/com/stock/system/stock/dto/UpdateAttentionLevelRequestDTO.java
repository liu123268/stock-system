package com.stock.system.stock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record UpdateAttentionLevelRequestDTO(
        @NotNull(message = "研究周期不能为空")
        @Positive(message = "研究周期不合法")
        Long researchCycleId,
        @NotBlank(message = "关注级别不能为空")
        @Pattern(regexp = "NORMAL|FOCUS", message = "关注级别不合法")
        String attentionLevel
) {
}
