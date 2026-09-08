package com.stock.system.workspace.vo;

public record JoinWorkspaceResponseVO(
        WorkspaceSummaryResponseVO workspace,
        boolean newlyJoined
) {
}
