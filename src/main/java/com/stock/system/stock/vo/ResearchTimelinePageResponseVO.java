package com.stock.system.stock.vo;

import java.util.List;

public record ResearchTimelinePageResponseVO(
        List<ResearchCycleDetailResponseVO> cycles,
        String nextCursor
) {
}
