package com.stock.system.workspace.vo;

import java.util.List;

public record WorkspaceOverviewResponseVO(
        long activeStockCount,
        List<WorkspaceActivityResponseVO> recentActivities
) {
}
