package com.stock.system.admin.service;

import com.stock.system.admin.dto.AdminUserQueryDTO;
import com.stock.system.admin.dto.AdminWorkspaceQueryDTO;
import com.stock.system.admin.dto.OperationLogQueryDTO;
import com.stock.system.admin.vo.AdminOperationLogResponseVO;
import com.stock.system.admin.vo.AdminUserDetailResponseVO;
import com.stock.system.admin.vo.AdminUserSummaryResponseVO;
import com.stock.system.admin.vo.AdminWorkspaceDetailResponseVO;
import com.stock.system.admin.vo.AdminWorkspaceSummaryResponseVO;
import com.stock.system.common.api.PageResponseVO;
import com.stock.system.common.exception.BusinessException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminGovernanceService {

    private final AdminQueryService queryService;
    private final AdminGovernanceTransactionService transactionService;
    private final AdminOperationLogService operationLogService;

    public AdminGovernanceService(
            AdminQueryService queryService,
            AdminGovernanceTransactionService transactionService,
            AdminOperationLogService operationLogService
    ) {
        this.queryService = queryService;
        this.transactionService = transactionService;
        this.operationLogService = operationLogService;
    }

    public PageResponseVO<AdminUserSummaryResponseVO> users(
            Long actorUserId,
            AdminUserQueryDTO query
    ) {
        return queryService.users(query);
    }

    public AdminUserDetailResponseVO user(Long actorUserId, Long targetUserId) {
        return queryService.user(targetUserId);
    }

    public AdminUserDetailResponseVO disableUser(Long actorUserId, Long targetUserId) {
        try {
            return transactionService.disableUser(actorUserId, targetUserId);
        } catch (BusinessException exception) {
            operationLogService.recordFailure("DISABLE_USER", targetUserId, "DISABLED");
            throw exception;
        }
    }

    public AdminUserDetailResponseVO restoreUser(Long actorUserId, Long targetUserId) {
        try {
            return transactionService.restoreUser(actorUserId, targetUserId);
        } catch (BusinessException exception) {
            operationLogService.recordFailure("RESTORE_USER", targetUserId, "ACTIVE");
            throw exception;
        }
    }

    public PageResponseVO<AdminWorkspaceSummaryResponseVO> workspaces(
            Long actorUserId,
            AdminWorkspaceQueryDTO query
    ) {
        return queryService.workspaces(query);
    }

    public AdminWorkspaceDetailResponseVO workspace(Long actorUserId, Long workspaceId) {
        return queryService.workspace(workspaceId);
    }

    public AdminWorkspaceDetailResponseVO archiveWorkspace(Long actorUserId, Long workspaceId) {
        try {
            return transactionService.archiveWorkspace(actorUserId, workspaceId);
        } catch (BusinessException exception) {
            operationLogService.recordFailure(
                    "ARCHIVE_WORKSPACE", workspaceId, "ARCHIVED_BY_SUPER_ADMIN");
            throw exception;
        }
    }

    public AdminWorkspaceDetailResponseVO restoreWorkspace(Long actorUserId, Long workspaceId) {
        try {
            return transactionService.restoreWorkspace(actorUserId, workspaceId);
        } catch (BusinessException exception) {
            operationLogService.recordFailure("RESTORE_WORKSPACE", workspaceId, "ACTIVE");
            throw exception;
        }
    }

    public PageResponseVO<AdminOperationLogResponseVO> operationLogs(
            Long actorUserId,
            OperationLogQueryDTO query
    ) {
        return queryService.operationLogs(query);
    }
}
