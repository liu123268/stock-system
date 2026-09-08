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
import com.stock.system.workspace.vo.WorkspaceDetailResponseVO;
import com.stock.system.workspace.vo.WorkspaceMemberResponseVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkspaceMemberService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceService workspaceService;
    private final UserService userService;
    private final SuperAdminService superAdminService;

    public WorkspaceMemberService(
            WorkspaceMapper workspaceMapper,
            WorkspaceMemberMapper memberMapper,
            WorkspaceService workspaceService,
            UserService userService,
            SuperAdminService superAdminService
    ) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
        this.workspaceService = workspaceService;
        this.userService = userService;
        this.superAdminService = superAdminService;
    }

    public List<WorkspaceMemberResponseVO> list(Long userId, Long workspaceId) {
        workspaceService.detail(userId, workspaceId);
        return memberMapper.selectActiveMembers(workspaceId, userId);
    }

    @Transactional
    public void leave(Long userId, Long workspaceId) {
        workspaceService.requireActiveRegularUser(userId);
        Workspace workspace = requireWorkspaceForUpdate(workspaceId);
        WorkspaceMember member = memberMapper.selectForUpdate(workspaceId, userId);
        if (member == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (workspace.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.OWNER_CANNOT_LEAVE);
        }
        if ("LEFT".equals(member.getStatus())) {
            return;
        }
        if (!"ACTIVE".equals(member.getStatus())) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_ACTIVE);
        }
        member.setStatus("LEFT");
        member.setLastInactiveAt(LocalDateTime.now());
        memberMapper.updateById(member);
    }

    @Transactional
    public void remove(Long userId, Long workspaceId, Long targetUserId) {
        workspaceService.requireActiveRegularUser(userId);
        Workspace workspace = requireOwnerForUpdate(userId, workspaceId);
        requireActiveWorkspace(workspace);
        if (workspace.getOwnerUserId().equals(targetUserId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }

        WorkspaceMember target = memberMapper.selectForUpdate(workspaceId, targetUserId);
        if (target == null) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_FOUND);
        }
        if ("REMOVED".equals(target.getStatus())) {
            return;
        }
        if (!"ACTIVE".equals(target.getStatus())) {
            throw new BusinessException(ErrorCode.MEMBER_NOT_ACTIVE);
        }
        target.setStatus("REMOVED");
        target.setLastInactiveAt(LocalDateTime.now());
        memberMapper.updateById(target);
    }

    @Transactional
    public WorkspaceDetailResponseVO transfer(
            Long userId,
            Long workspaceId,
            Long targetUserId
    ) {
        workspaceService.requireActiveRegularUser(userId);
        Workspace workspace = requireOwnerForUpdate(userId, workspaceId);
        requireActiveWorkspace(workspace);
        if (workspace.getOwnerUserId().equals(targetUserId)) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_NOT_ACTIVE_MEMBER);
        }

        WorkspaceMember target = memberMapper.selectForUpdate(workspaceId, targetUserId);
        AppUser targetUser = userService.findById(targetUserId);
        if (target == null
                || !"ACTIVE".equals(target.getStatus())
                || targetUser == null
                || !"ACTIVE".equals(targetUser.getStatus())
                || superAdminService.isSuperAdmin(targetUserId)) {
            throw new BusinessException(ErrorCode.TRANSFER_TARGET_NOT_ACTIVE_MEMBER);
        }

        workspace.setOwnerUserId(targetUserId);
        workspaceMapper.updateById(workspace);
        return workspaceService.detail(userId, workspaceId);
    }

    private Workspace requireWorkspaceForUpdate(Long workspaceId) {
        Workspace workspace = workspaceMapper.selectByIdForUpdate(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        return workspace;
    }

    private Workspace requireOwnerForUpdate(Long userId, Long workspaceId) {
        Workspace workspace = requireWorkspaceForUpdate(workspaceId);
        WorkspaceMember operator = memberMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId)
        );
        if (operator == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if (!"ACTIVE".equals(operator.getStatus())) {
            throw new BusinessException(ErrorCode.WORKSPACE_MEMBERSHIP_INACTIVE);
        }
        if (!workspace.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        return workspace;
    }

    private void requireActiveWorkspace(Workspace workspace) {
        if (!"ACTIVE".equals(workspace.getStatus())) {
            throw new BusinessException(ErrorCode.WORKSPACE_ARCHIVED);
        }
    }
}
