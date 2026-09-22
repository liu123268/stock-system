package com.stock.system.admin.service;

import com.stock.system.admin.vo.AdminUserDetailResponseVO;
import com.stock.system.admin.vo.AdminWorkspaceDetailResponseVO;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.service.WorkspaceService;
import com.stock.system.workspace.service.WorkspaceStateChange;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminGovernanceTransactionService {

    private final UserService userService;
    private final WorkspaceService workspaceService;
    private final AdminOperationLogService operationLogService;
    private final AdminQueryService queryService;

    public AdminGovernanceTransactionService(
            UserService userService,
            WorkspaceService workspaceService,
            AdminOperationLogService operationLogService,
            AdminQueryService queryService
    ) {
        this.userService = userService;
        this.workspaceService = workspaceService;
        this.operationLogService = operationLogService;
        this.queryService = queryService;
    }

    @Transactional
    public AdminUserDetailResponseVO disableUser(Long actorUserId, Long targetUserId) {
        if (actorUserId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.SUPER_ADMIN_CANNOT_DISABLE_SELF);
        }
        AppUser target = requireUserForUpdate(targetUserId);
        String beforeStatus = target.getStatus();
        if (!"DISABLED".equals(beforeStatus)) {
            target.setStatus("DISABLED");
            userService.update(target);
        }
        operationLogService.recordSuccess(
                "DISABLE_USER", targetUserId, beforeStatus, target.getStatus());
        return queryService.user(targetUserId);
    }

    @Transactional
    public AdminUserDetailResponseVO restoreUser(Long actorUserId, Long targetUserId) {
        AppUser target = requireUserForUpdate(targetUserId);
        String beforeStatus = target.getStatus();
        if (!"ACTIVE".equals(beforeStatus)) {
            target.setStatus("ACTIVE");
            userService.update(target);
        }
        operationLogService.recordSuccess(
                "RESTORE_USER", targetUserId, beforeStatus, target.getStatus());
        return queryService.user(targetUserId);
    }

    @Transactional
    public AdminWorkspaceDetailResponseVO archiveWorkspace(Long actorUserId, Long workspaceId) {
        WorkspaceStateChange change = workspaceService.archiveBySuperAdmin(workspaceId);
        operationLogService.recordSuccess(
                "ARCHIVE_WORKSPACE", workspaceId, change.beforeStatus(), change.afterStatus());
        return queryService.workspace(workspaceId);
    }

    @Transactional
    public AdminWorkspaceDetailResponseVO restoreWorkspace(Long actorUserId, Long workspaceId) {
        WorkspaceStateChange change = workspaceService.restoreBySuperAdmin(workspaceId);
        operationLogService.recordSuccess(
                "RESTORE_WORKSPACE", workspaceId, change.beforeStatus(), change.afterStatus());
        return queryService.workspace(workspaceId);
    }

    private AppUser requireUserForUpdate(Long userId) {
        AppUser user = userService.findByIdForUpdate(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }
}
