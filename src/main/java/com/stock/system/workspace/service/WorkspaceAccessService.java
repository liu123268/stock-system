package com.stock.system.workspace.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.admin.service.SuperAdminService;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.entity.Workspace;
import com.stock.system.workspace.entity.WorkspaceMember;
import com.stock.system.workspace.mapper.WorkspaceMapper;
import com.stock.system.workspace.mapper.WorkspaceMemberMapper;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceAccessService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final UserService userService;
    private final SuperAdminService superAdminService;

    public WorkspaceAccessService(
            WorkspaceMapper workspaceMapper,
            WorkspaceMemberMapper memberMapper,
            UserService userService,
            SuperAdminService superAdminService
    ) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
        this.userService = userService;
        this.superAdminService = superAdminService;
    }

    public void requireActiveRegularUser(Long userId) {
        AppUser user = userService.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (superAdminService.isSuperAdmin(userId)) {
            throw new BusinessException(ErrorCode.SUPER_ADMIN_CANNOT_USE_WORKSPACE);
        }
    }

    public void requireActiveMemberForUpdate(Long userId, Long workspaceId) {
        requireActiveRegularUser(userId);
        Workspace workspace = requireWorkspace(workspaceId, true);
        requireActiveMembership(userId, workspaceId);
        requireActiveWorkspace(workspace);
    }

    public Workspace requireMember(Long userId, Long workspaceId) {
        requireActiveRegularUser(userId);
        Workspace workspace = requireWorkspace(workspaceId, false);
        requireActiveMembership(userId, workspaceId);
        return workspace;
    }

    public Workspace requireOwner(Long userId, Long workspaceId) {
        return requireOwner(userId, workspaceId, false, false);
    }

    public Workspace requireOwnerForUpdate(Long userId, Long workspaceId) {
        return requireOwner(userId, workspaceId, true, false);
    }

    public Workspace requireActiveOwnerForUpdate(Long userId, Long workspaceId) {
        return requireOwner(userId, workspaceId, true, true);
    }

    private Workspace requireOwner(
            Long userId,
            Long workspaceId,
            boolean lockWorkspace,
            boolean requireActiveWorkspace
    ) {
        requireActiveRegularUser(userId);
        Workspace workspace = requireWorkspace(workspaceId, lockWorkspace);
        requireActiveMembership(userId, workspaceId);
        if (!workspace.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        if (requireActiveWorkspace) {
            requireActiveWorkspace(workspace);
        }
        return workspace;
    }

    private Workspace requireWorkspace(Long workspaceId, boolean lockWorkspace) {
        Workspace workspace = lockWorkspace
                ? workspaceMapper.selectByIdForUpdate(workspaceId)
                : workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        return workspace;
    }

    private void requireActiveMembership(Long userId, Long workspaceId) {
        WorkspaceMember membership = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId)
        );
        if (membership == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (!"ACTIVE".equals(membership.getStatus())) {
            throw new BusinessException(ErrorCode.WORKSPACE_MEMBERSHIP_INACTIVE);
        }
    }

    private void requireActiveWorkspace(Workspace workspace) {
        if (!"ACTIVE".equals(workspace.getStatus())) {
            throw new BusinessException(ErrorCode.WORKSPACE_ARCHIVED);
        }
    }
}
