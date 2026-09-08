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
import com.stock.system.workspace.mapper.WorkspaceInvitationMapper;
import com.stock.system.workspace.mapper.WorkspaceMemberMapper;
import com.stock.system.workspace.vo.WorkspaceDetailResponseVO;
import com.stock.system.workspace.vo.WorkspaceSummaryResponseVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceInvitationMapper invitationMapper;
    private final UserService userService;
    private final SuperAdminService superAdminService;

    public WorkspaceService(
            WorkspaceMapper workspaceMapper,
            WorkspaceMemberMapper memberMapper,
            WorkspaceInvitationMapper invitationMapper,
            UserService userService,
            SuperAdminService superAdminService
    ) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
        this.invitationMapper = invitationMapper;
        this.userService = userService;
        this.superAdminService = superAdminService;
    }

    public List<WorkspaceSummaryResponseVO> list(Long userId) {
        requireActiveRegularUser(userId);
        List<Long> workspaceIds = memberMapper.selectList(
                        new LambdaQueryWrapper<WorkspaceMember>()
                                .eq(WorkspaceMember::getUserId, userId)
                                .eq(WorkspaceMember::getStatus, "ACTIVE")
                ).stream()
                .map(WorkspaceMember::getWorkspaceId)
                .toList();
        if (workspaceIds.isEmpty()) {
            return List.of();
        }

        return workspaceMapper.selectList(
                        new LambdaQueryWrapper<Workspace>()
                                .in(Workspace::getId, workspaceIds)
                                .orderByDesc(Workspace::getCreatedAt)
                                .orderByDesc(Workspace::getId)
                ).stream()
                .map(workspace -> toSummary(workspace, userId))
                .toList();
    }

    @Transactional
    public WorkspaceDetailResponseVO create(Long userId, String rawName) {
        requireActiveRegularUser(userId);
        String name = normalizeName(rawName);
        if (workspaceMapper.selectCount(
                new LambdaQueryWrapper<Workspace>().eq(Workspace::getName, name)
        ) > 0) {
            throw new BusinessException(ErrorCode.WORKSPACE_NAME_ALREADY_EXISTS);
        }

        Workspace workspace = new Workspace();
        workspace.setName(name);
        workspace.setCreatedByUserId(userId);
        workspace.setOwnerUserId(userId);
        workspace.setStatus("ACTIVE");

        try {
            workspaceMapper.insert(workspace);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.WORKSPACE_NAME_ALREADY_EXISTS);
        }

        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspace.getId());
        member.setUserId(userId);
        member.setStatus("ACTIVE");
        memberMapper.insert(member);

        return detail(userId, workspace.getId());
    }

    public WorkspaceDetailResponseVO detail(Long userId, Long workspaceId) {
        requireActiveRegularUser(userId);
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

        Workspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        return toDetail(workspace, userId);
    }

    public void requireActiveMemberForUpdate(Long userId, Long workspaceId) {
        requireActiveRegularUser(userId);
        Workspace workspace = workspaceMapper.selectByIdForUpdate(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
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
        if (!"ACTIVE".equals(workspace.getStatus())) {
            throw new BusinessException(ErrorCode.WORKSPACE_ARCHIVED);
        }
    }

    @Transactional
    public WorkspaceDetailResponseVO archive(Long userId, Long workspaceId) {
        Workspace workspace = requireOwnerForUpdate(userId, workspaceId);
        if ("ARCHIVED_BY_OWNER".equals(workspace.getStatus())) {
            return toDetail(workspace, userId);
        }
        if (!"ACTIVE".equals(workspace.getStatus())) {
            throw new BusinessException(ErrorCode.WORKSPACE_ARCHIVED);
        }

        workspace.setStatus("ARCHIVED_BY_OWNER");
        workspace.setArchivedAt(LocalDateTime.now());
        workspaceMapper.updateArchiveState(
                workspace.getId(),
                workspace.getStatus(),
                workspace.getArchivedAt()
        );

        var currentInvitation = invitationMapper.selectCurrentForUpdate(workspaceId);
        if (currentInvitation != null) {
            currentInvitation.setStatus("REVOKED");
            invitationMapper.updateById(currentInvitation);
        }
        return toDetail(workspace, userId);
    }

    @Transactional
    public WorkspaceDetailResponseVO restore(Long userId, Long workspaceId) {
        Workspace workspace = requireOwnerForUpdate(userId, workspaceId);
        if ("ACTIVE".equals(workspace.getStatus())) {
            return toDetail(workspace, userId);
        }
        if (!"ARCHIVED_BY_OWNER".equals(workspace.getStatus())) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }

        workspace.setStatus("ACTIVE");
        workspace.setArchivedAt(null);
        workspaceMapper.updateArchiveState(workspace.getId(), workspace.getStatus(), null);
        return toDetail(workspace, userId);
    }

    private Workspace requireOwnerForUpdate(Long userId, Long workspaceId) {
        requireActiveRegularUser(userId);
        Workspace workspace = workspaceMapper.selectByIdForUpdate(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
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
        if (!workspace.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        }
        return workspace;
    }

    void requireActiveRegularUser(Long userId) {
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

    private String normalizeName(String rawName) {
        StringBuilder normalized = new StringBuilder();
        rawName.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
                .forEach(normalized::appendCodePoint);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 30) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return normalized.toString();
    }

    private WorkspaceSummaryResponseVO toSummary(Workspace workspace, Long userId) {
        return new WorkspaceSummaryResponseVO(
                workspace.getId(),
                workspace.getName(),
                roleOf(workspace, userId),
                workspace.getStatus(),
                activeMemberCount(workspace.getId()),
                workspace.getCreatedAt()
        );
    }

    private WorkspaceDetailResponseVO toDetail(Workspace workspace, Long userId) {
        return new WorkspaceDetailResponseVO(
                workspace.getId(),
                workspace.getName(),
                roleOf(workspace, userId),
                workspace.getStatus(),
                activeMemberCount(workspace.getId()),
                workspace.getCreatedByUserId(),
                workspace.getOwnerUserId(),
                workspace.getArchivedAt(),
                workspace.getCreatedAt()
        );
    }

    private String roleOf(Workspace workspace, Long userId) {
        return workspace.getOwnerUserId().equals(userId) ? "OWNER" : "MEMBER";
    }

    private long activeMemberCount(Long workspaceId) {
        return memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getStatus, "ACTIVE")
        );
    }
}
