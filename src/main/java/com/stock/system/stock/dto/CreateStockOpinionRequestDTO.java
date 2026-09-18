package com.stock.system.stock.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateStockOpinionRequestDTO(
        @NotBlank(message = "观点类型不能为空")
        @Pattern(regexp = "UPDATE|REVIEW", message = "观点类型不合法")
        String type,
        @NotBlank(message = "观点倾向不能为空")
        @Pattern(regexp = "BULLISH|NEUTRAL|BEARISH", message = "观点倾向不合法")
        String stance,
        @NotBlank(message = "判断理由不能为空")
        @Size(max = 1000, message = "判断理由不能超过 1000 个字符")
        String reason,
        @Size(max = 1000, message = "关键观察条件不能超过 1000 个字符")
        String observationNote,
        @NotBlank(message = "关注周期不能为空")
        @Pattern(regexp = "SHORT|MEDIUM|LONG", message = "关注周期不合法")
        String horizon,
        @DecimalMin(value = "0.0001", message = "目标价必须大于 0")
        @Digits(integer = 14, fraction = 4, message = "目标价格式不合法")
        BigDecimal targetPrice,
        @DecimalMin(value = "0.0001", message = "止损参考价必须大于 0")
        @Digits(integer = 14, fraction = 4, message = "止损参考价格式不合法")
        BigDecimal stopLossPrice
) {
}
