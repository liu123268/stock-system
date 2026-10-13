package com.stock.system.stock.dto;

import java.time.LocalDate;

/** 日期均按项目时区的自然日解释，起止日期包含当天；作者 ID 只缩小已授权周期。 */
public record ResearchSourceFilter(Long authorUserId, LocalDate dateFrom, LocalDate dateTo) {
    public static ResearchSourceFilter fullCycle() {
        return new ResearchSourceFilter(null, null, null);
    }
}
