package com.stock.system.workspace.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
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
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceInvitationMapper invitationMapper;
    private final WorkspaceAccessService accessService;

    public WorkspaceService(
            WorkspaceMapper workspaceMapper,
            WorkspaceMemberMapper memberMapper,
            WorkspaceInvitationMapper invitationMapper,
            WorkspaceAccessService accessService
    ) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
        this.invitationMapper = invitationMapper;
        this.accessService = accessService;
    }

    public List<WorkspaceSummaryResponseVO> list(Long userId) {
        accessService.requireActiveRegularUser(userId);
        return workspaceMapper.selectSummariesForUser(userId);
    }

    @Transactional
    public WorkspaceDetailResponseVO create(Long userId, String rawName) {
        accessService.requireActiveRegularUser(userId);
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
        return toDetail(accessService.requireMember(userId, workspaceId), userId);
    }

    public void requireActiveMemberForUpdate(Long userId, Long workspaceId) {
        accessService.requireActiveMemberForUpdate(userId, workspaceId);
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

    @Transactional(propagation = Propagation.MANDATORY)
    public WorkspaceStateChange archiveBySuperAdmin(Long workspaceId) {
        Workspace workspace = workspaceMapper.selectByIdForUpdate(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if ("ARCHIVED_BY_SUPER_ADMIN".equals(workspace.getStatus())) {
            return new WorkspaceStateChange(workspace.getStatus(), workspace.getStatus());
        }
        if (!"ACTIVE".equals(workspace.getStatus())) {
            throw new BusinessException(
                    ErrorCode.WORKSPACE_ACCESS_DENIED,
                    "由空间所有者归档的空间只能由所有者恢复"
            );
        }

        String beforeStatus = workspace.getStatus();
        workspace.setStatus("ARCHIVED_BY_SUPER_ADMIN");
        workspace.setArchivedAt(LocalDateTime.now());
        workspaceMapper.updateArchiveState(workspaceId, workspace.getStatus(), workspace.getArchivedAt());
        var currentInvitation = invitationMapper.selectCurrentForUpdate(workspaceId);
        if (currentInvitation != null) {
            currentInvitation.setStatus("REVOKED");
            invitationMapper.updateById(currentInvitation);
        }
        return new WorkspaceStateChange(beforeStatus, workspace.getStatus());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WorkspaceStateChange restoreBySuperAdmin(Long workspaceId) {
        Workspace workspace = workspaceMapper.selectByIdForUpdate(workspaceId);
        if (workspace == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND);
        }
        if ("ACTIVE".equals(workspace.getStatus())) {
            return new WorkspaceStateChange(workspace.getStatus(), workspace.getStatus());
        }
        if (!"ARCHIVED_BY_SUPER_ADMIN".equals(workspace.getStatus())) {
            throw new BusinessException(
                    ErrorCode.WORKSPACE_ACCESS_DENIED,
                    "由空间所有者归档的空间只能由所有者恢复"
            );
        }

        String beforeStatus = workspace.getStatus();
        workspace.setStatus("ACTIVE");
        workspace.setArchivedAt(null);
        workspaceMapper.updateArchiveState(workspaceId, workspace.getStatus(), null);
        return new WorkspaceStateChange(beforeStatus, workspace.getStatus());
    }

    private Workspace requireOwnerForUpdate(Long userId, Long workspaceId) {
        return accessService.requireOwnerForUpdate(userId, workspaceId);
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
