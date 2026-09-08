package com.stock.system.workspace.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.workspace.entity.Workspace;
import com.stock.system.workspace.entity.WorkspaceInvitation;
import com.stock.system.workspace.entity.WorkspaceMember;
import com.stock.system.workspace.mapper.WorkspaceInvitationMapper;
import com.stock.system.workspace.mapper.WorkspaceMapper;
import com.stock.system.workspace.mapper.WorkspaceMemberMapper;
import com.stock.system.workspace.vo.CurrentInvitationResponseVO;
import com.stock.system.workspace.vo.InvitationHistoryResponseVO;
import com.stock.system.workspace.vo.JoinWorkspaceResponseVO;
import com.stock.system.workspace.vo.WorkspaceDetailResponseVO;
import com.stock.system.workspace.vo.WorkspaceSummaryResponseVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
public class WorkspaceInvitationService {

    private static final String INVITATION_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int INVITATION_LENGTH = 6;
    private static final int MAX_USES = 5;
    private static final int CODE_GENERATION_ATTEMPTS = 10;

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final WorkspaceInvitationMapper invitationMapper;
    private final WorkspaceService workspaceService;
    private final SecureRandom secureRandom = new SecureRandom();

    public WorkspaceInvitationService(
            WorkspaceMapper workspaceMapper,
            WorkspaceMemberMapper memberMapper,
            WorkspaceInvitationMapper invitationMapper,
            WorkspaceService workspaceService
    ) {
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
        this.invitationMapper = invitationMapper;
        this.workspaceService = workspaceService;
    }

    public CurrentInvitationResponseVO current(Long userId, Long workspaceId) {
        requireOwner(userId, workspaceId, false);
        WorkspaceInvitation invitation = findCurrent(workspaceId);
        return invitation == null ? null : toCurrent(invitation);
    }

    public List<InvitationHistoryResponseVO> history(Long userId, Long workspaceId) {
        requireOwner(userId, workspaceId, false);
        return invitationMapper.selectList(
                        new LambdaQueryWrapper<WorkspaceInvitation>()
                                .eq(WorkspaceInvitation::getWorkspaceId, workspaceId)
                                .orderByDesc(WorkspaceInvitation::getCreatedAt)
                                .orderByDesc(WorkspaceInvitation::getId)
                ).stream()
                .map(this::toHistory)
                .toList();
    }

    @Transactional
    public CurrentInvitationResponseVO create(
            Long userId,
            Long workspaceId,
            Long currentInvitationId
    ) {
        requireOwnerForUpdate(userId, workspaceId);
        WorkspaceInvitation current = invitationMapper.selectCurrentForUpdate(workspaceId);
        Long actualCurrentId = current == null ? null : current.getId();
        if (!Objects.equals(currentInvitationId, actualCurrentId)) {
            throw new BusinessException(ErrorCode.INVITATION_CHANGED);
        }

        if (current != null) {
            current.setStatus("REVOKED");
            invitationMapper.updateById(current);
        }

        LocalDateTime now = LocalDateTime.now();
        for (int attempt = 0; attempt < CODE_GENERATION_ATTEMPTS; attempt++) {
            WorkspaceInvitation invitation = new WorkspaceInvitation();
            invitation.setWorkspaceId(workspaceId);
            invitation.setInviteCode(generateCode());
            invitation.setCreatedByUserId(userId);
            invitation.setExpiresAt(now.plusDays(7));
            invitation.setUsedCount(0);
            invitation.setStatus("ACTIVE");
            invitation.setCreatedAt(now);
            try {
                invitationMapper.insert(invitation);
                return toCurrent(invitation);
            } catch (DuplicateKeyException exception) {
                if (attempt == CODE_GENERATION_ATTEMPTS - 1) {
                    throw exception;
                }
            }
        }
        throw new BusinessException(ErrorCode.CONCURRENT_OPERATION_CONFLICT);
    }

    @Transactional
    public void revoke(Long userId, Long workspaceId, Long invitationId) {
        requireOwnerForUpdate(userId, workspaceId);
        WorkspaceInvitation current = invitationMapper.selectCurrentForUpdate(workspaceId);
        if (current == null || !current.getId().equals(invitationId)) {
            throw new BusinessException(ErrorCode.INVITATION_CHANGED);
        }
        current.setStatus("REVOKED");
        invitationMapper.updateById(current);
    }

    @Transactional
    public JoinWorkspaceResponseVO join(Long userId, String inviteCode) {
        workspaceService.requireActiveRegularUser(userId);
        WorkspaceInvitation candidate = invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getInviteCode, inviteCode)
        );
        if (candidate == null) {
            throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND);
        }

        Workspace workspace = workspaceMapper.selectByIdForUpdate(candidate.getWorkspaceId());
        if (workspace == null) {
            throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND);
        }
        if (!"ACTIVE".equals(workspace.getStatus())) {
            throw new BusinessException(ErrorCode.WORKSPACE_ARCHIVED);
        }

        WorkspaceInvitation invitation = invitationMapper.selectByIdForUpdate(candidate.getId());
        validateUsable(invitation, inviteCode, LocalDateTime.now());

        WorkspaceMember member = memberMapper.selectForUpdate(workspace.getId(), userId);
        if (member != null && "ACTIVE".equals(member.getStatus())) {
            return new JoinWorkspaceResponseVO(toSummary(userId, workspace.getId()), false);
        }
        if (member != null
                && "REMOVED".equals(member.getStatus())
                && !invitation.getCreatedAt().isAfter(member.getLastInactiveAt())) {
            throw new BusinessException(ErrorCode.REMOVED_MEMBER_REQUIRES_NEW_INVITATION);
        }

        LocalDateTime joinedAt = LocalDateTime.now();
        if (member == null) {
            member = new WorkspaceMember();
            member.setWorkspaceId(workspace.getId());
            member.setUserId(userId);
            member.setStatus("ACTIVE");
            member.setFirstJoinedAt(joinedAt);
            member.setLastJoinedAt(joinedAt);
            memberMapper.insert(member);
        } else {
            member.setStatus("ACTIVE");
            member.setLastJoinedAt(joinedAt);
            memberMapper.updateById(member);
        }

        invitation.setUsedCount(invitation.getUsedCount() + 1);
        invitationMapper.updateById(invitation);
        return new JoinWorkspaceResponseVO(toSummary(userId, workspace.getId()), true);
    }

    private Workspace requireOwnerForUpdate(Long userId, Long workspaceId) {
        workspaceService.requireActiveRegularUser(userId);
        Workspace workspace = workspaceMapper.selectByIdForUpdate(workspaceId);
        return requireOwner(userId, workspaceId, workspace, true);
    }

    private Workspace requireOwner(Long userId, Long workspaceId, boolean requireActive) {
        workspaceService.requireActiveRegularUser(userId);
        Workspace workspace = workspaceMapper.selectById(workspaceId);
        return requireOwner(userId, workspaceId, workspace, requireActive);
    }

    private Workspace requireOwner(
            Long userId,
            Long workspaceId,
            Workspace workspace,
            boolean requireActive
    ) {
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
        if (requireActive && !"ACTIVE".equals(workspace.getStatus())) {
            throw new BusinessException(ErrorCode.WORKSPACE_ARCHIVED);
        }
        return workspace;
    }

    private WorkspaceInvitation findCurrent(Long workspaceId) {
        return invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getWorkspaceId, workspaceId)
                        .eq(WorkspaceInvitation::getStatus, "ACTIVE")
        );
    }

    private void validateUsable(
            WorkspaceInvitation invitation,
            String inviteCode,
            LocalDateTime now
    ) {
        if (invitation == null || !invitation.getInviteCode().equals(inviteCode)) {
            throw new BusinessException(ErrorCode.INVITATION_NOT_FOUND);
        }
        if (!"ACTIVE".equals(invitation.getStatus())) {
            throw new BusinessException(ErrorCode.INVITATION_REVOKED);
        }
        if (invitation.getUsedCount() >= MAX_USES) {
            throw new BusinessException(ErrorCode.INVITATION_EXHAUSTED);
        }
        if (!invitation.getExpiresAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.INVITATION_EXPIRED);
        }
    }

    private String invitationStatus(WorkspaceInvitation invitation) {
        if (!"ACTIVE".equals(invitation.getStatus())) {
            return "REVOKED";
        }
        if (invitation.getUsedCount() >= MAX_USES) {
            return "EXHAUSTED";
        }
        if (!invitation.getExpiresAt().isAfter(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    private CurrentInvitationResponseVO toCurrent(WorkspaceInvitation invitation) {
        return new CurrentInvitationResponseVO(
                invitation.getId(),
                invitation.getInviteCode(),
                invitationStatus(invitation),
                invitation.getUsedCount(),
                Math.max(0, MAX_USES - invitation.getUsedCount()),
                invitation.getExpiresAt(),
                invitation.getCreatedAt()
        );
    }

    private InvitationHistoryResponseVO toHistory(WorkspaceInvitation invitation) {
        return new InvitationHistoryResponseVO(
                invitation.getId(),
                invitationStatus(invitation),
                invitation.getUsedCount(),
                Math.max(0, MAX_USES - invitation.getUsedCount()),
                invitation.getExpiresAt(),
                invitation.getCreatedAt()
        );
    }

    private WorkspaceSummaryResponseVO toSummary(Long userId, Long workspaceId) {
        WorkspaceDetailResponseVO detail = workspaceService.detail(userId, workspaceId);
        return new WorkspaceSummaryResponseVO(
                detail.workspaceId(),
                detail.name(),
                detail.role(),
                detail.status(),
                detail.memberCount(),
                detail.createdAt()
        );
    }

    private String generateCode() {
        StringBuilder code = new StringBuilder(INVITATION_LENGTH);
        for (int index = 0; index < INVITATION_LENGTH; index++) {
            code.append(INVITATION_ALPHABET.charAt(secureRandom.nextInt(INVITATION_ALPHABET.length())));
        }
        return code.toString();
    }
}
