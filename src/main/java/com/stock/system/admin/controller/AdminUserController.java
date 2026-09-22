package com.stock.system.admin.controller;

import com.stock.system.admin.dto.AdminUserQueryDTO;
import com.stock.system.admin.service.AdminGovernanceService;
import com.stock.system.admin.vo.AdminUserDetailResponseVO;
import com.stock.system.admin.vo.AdminUserSummaryResponseVO;
import com.stock.system.common.api.PageResponseVO;
import com.stock.system.common.api.Result;
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
public class AdminUserController {

    private final AdminGovernanceService governanceService;

    public AdminUserController(AdminGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @GetMapping
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
    public Result<AdminUserDetailResponseVO> detail(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long userId
    ) {
        return Result.success(governanceService.user(currentUser.userId(), userId));
    }

    @PostMapping("/{userId}/disable")
    public Result<AdminUserDetailResponseVO> disable(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long userId
    ) {
        return Result.success(governanceService.disableUser(currentUser.userId(), userId));
    }

    @PostMapping("/{userId}/restore")
    public Result<AdminUserDetailResponseVO> restore(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long userId
    ) {
        return Result.success(governanceService.restoreUser(currentUser.userId(), userId));
    }
}
