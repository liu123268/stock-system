package com.stock.system.admin.controller;

import com.stock.system.admin.dto.AdminUserQueryDTO;
import com.stock.system.admin.service.AdminGovernanceService;
import com.stock.system.admin.vo.AdminUserDetailResponseVO;
import com.stock.system.admin.vo.AdminUserSummaryResponseVO;
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
@RequestMapping("/api/admin/users")
@Tag(name = "09 管理员账号治理", description = "仅超级管理员可访问。")
public class AdminUserController {

    private final AdminGovernanceService governanceService;

    public AdminUserController(AdminGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @GetMapping
    @Operation(summary = "分页查询账号")
    public Result<PageResponseVO<AdminUserSummaryResponseVO>> list(
            @AuthenticationPrincipal LoginUser currentUser,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword
    ) {
        return Result.success(governanceService.users(
                currentUser.userId(), new AdminUserQueryDTO(page, size, status, keyword)));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "读取账号治理详情")
    public Result<AdminUserDetailResponseVO> detail(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long userId
    ) {
        return Result.success(governanceService.user(currentUser.userId(), userId));
    }

    @PostMapping("/{userId}/disable")
    @Operation(summary = "禁用账号并使其旧 Token 失效")
    public Result<AdminUserDetailResponseVO> disable(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long userId
    ) {
        return Result.success(governanceService.disableUser(currentUser.userId(), userId));
    }

    @PostMapping("/{userId}/restore")
    @Operation(summary = "恢复账号")
    public Result<AdminUserDetailResponseVO> restore(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long userId
    ) {
        return Result.success(governanceService.restoreUser(currentUser.userId(), userId));
    }
}
