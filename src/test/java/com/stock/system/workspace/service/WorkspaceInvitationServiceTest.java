package com.stock.system.workspace.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.workspace.entity.Workspace;
import com.stock.system.workspace.entity.WorkspaceInvitation;
import com.stock.system.workspace.mapper.WorkspaceInvitationMapper;
import com.stock.system.workspace.mapper.WorkspaceMapper;
import com.stock.system.workspace.mapper.WorkspaceMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceInvitationServiceTest {

    @Test
    void shouldReturnBusinessConflictAfterAllGeneratedCodesCollide() {
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        WorkspaceMemberMapper memberMapper = mock(WorkspaceMemberMapper.class);
        WorkspaceInvitationMapper invitationMapper = mock(WorkspaceInvitationMapper.class);
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        WorkspaceAccessService accessService = mock(WorkspaceAccessService.class);
        InvitationAttemptService attemptService = mock(InvitationAttemptService.class);
        Workspace workspace = new Workspace();
        workspace.setId(42L);
        when(accessService.requireActiveOwnerForUpdate(7L, 42L)).thenReturn(workspace);
        when(invitationMapper.insert(any(WorkspaceInvitation.class)))
                .thenThrow(new DuplicateKeyException("controlled invitation code collision"));

        WorkspaceInvitationService service = new WorkspaceInvitationService(
                workspaceMapper,
                memberMapper,
                invitationMapper,
                workspaceService,
                accessService,
                attemptService
        );

        BusinessException exception = catchThrowableOfType(
                () -> service.create(7L, 42L, null),
                BusinessException.class
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONCURRENT_OPERATION_CONFLICT);
        verify(invitationMapper, times(10)).insert(any(WorkspaceInvitation.class));
    }
}
