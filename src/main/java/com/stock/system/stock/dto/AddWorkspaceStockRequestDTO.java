package com.stock.system.stock.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AddWorkspaceStockRequestDTO(
        @NotBlank(message = "股票代码不能为空")
        @Size(max = 20, message = "股票代码最多20个字符")
        String code,

        @NotBlank(message = "观点倾向不能为空")
        @Pattern(regexp = "BULLISH|NEUTRAL|BEARISH", message = "观点倾向不合法")
        String stance,

        @NotBlank(message = "判断理由不能为空")
        @Size(max = 1000, message = "判断理由最多1000个字符")
        String reason,

        @Size(max = 1000, message = "关键观察条件最多1000个字符")
        String observationNote,

        @NotBlank(message = "关注周期不能为空")
        @Pattern(regexp = "SHORT|MEDIUM|LONG", message = "关注周期不合法")
        String horizon,

        @DecimalMin(value = "0", inclusive = false, message = "目标价必须大于0")
        @Digits(integer = 14, fraction = 4, message = "目标价格式不合法")
        BigDecimal targetPrice,

        @DecimalMin(value = "0", inclusive = false, message = "止损参考价必须大于0")
        @Digits(integer = 14, fraction = 4, message = "止损参考价格式不合法")
        BigDecimal stopLossPrice
) {
}
