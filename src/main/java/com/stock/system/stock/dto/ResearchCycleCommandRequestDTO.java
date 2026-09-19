package com.stock.system.stock.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ResearchCycleCommandRequestDTO(
        @NotNull(message = "研究周期不能为空")
        @Positive(message = "研究周期不合法")
        Long researchCycleId
) {
}
