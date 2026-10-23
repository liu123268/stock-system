package com.stock.system.admin.controller;

import com.stock.system.admin.dto.OperationLogQueryDTO;
import com.stock.system.admin.service.AdminGovernanceService;
import com.stock.system.admin.vo.AdminOperationLogResponseVO;
import com.stock.system.common.api.PageResponseVO;
import com.stock.system.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.stock.system.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/operation-logs")
@Tag(name = "11 管理员操作日志", description = "仅超级管理员可访问。")
public class AdminOperationLogController {

    private final AdminGovernanceService governanceService;

    public AdminOperationLogController(AdminGovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @GetMapping
    @Operation(summary = "分页查询管理员操作日志")
    public Result<PageResponseVO<AdminOperationLogResponseVO>> list(
            @AuthenticationPrincipal LoginUser currentUser,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result
    ) {
        return Result.success(governanceService.operationLogs(
                currentUser.userId(), new OperationLogQueryDTO(page, size, action, result)));
    }
}
