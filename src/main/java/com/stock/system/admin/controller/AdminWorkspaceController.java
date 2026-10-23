package com.stock.system.admin.controller;

import com.stock.system.admin.dto.AdminWorkspaceQueryDTO;
import com.stock.system.admin.service.AdminGovernanceService;
import com.stock.system.admin.vo.AdminWorkspaceDetailResponseVO;
import com.stock.system.admin.vo.AdminWorkspaceSummaryResponseVO;
import com.stock.system.common.api.PageResponseVO;
import com.stock.system.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.stock.system.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/workspaces")
@Tag(name = "10 管理员空间治理", description = "仅超级管理员可访问，不返回成员观点正文。")
public class AdminWorkspaceController {

    private final AdminGovernanceService governanceService;

    public AdminWorkspaceController(AdminGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @GetMapping
    @Operation(summary = "分页查询空间")
    public Result<PageResponseVO<AdminWorkspaceSummaryResponseVO>> list(
            @AuthenticationPrincipal LoginUser currentUser,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(governanceService.workspaces(
                currentUser.userId(), new AdminWorkspaceQueryDTO(page, size, status, keyword)));
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "读取空间治理详情")
    public Result<AdminWorkspaceDetailResponseVO> detail(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(governanceService.workspace(currentUser.userId(), workspaceId));
    }

    @PostMapping("/{workspaceId}/archive")
    @Operation(summary = "由管理员归档空间")
    public Result<AdminWorkspaceDetailResponseVO> archive(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(governanceService.archiveWorkspace(currentUser.userId(), workspaceId));
    }

    @PostMapping("/{workspaceId}/restore")
    @Operation(summary = "由管理员恢复空间")
    public Result<AdminWorkspaceDetailResponseVO> restore(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(governanceService.restoreWorkspace(currentUser.userId(), workspaceId));
    }
}
