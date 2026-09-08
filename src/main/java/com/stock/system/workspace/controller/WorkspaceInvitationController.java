package com.stock.system.workspace.controller;

import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.workspace.dto.CreateInvitationRequestDTO;
import com.stock.system.workspace.service.WorkspaceInvitationService;
import com.stock.system.workspace.vo.CurrentInvitationResponseVO;
import com.stock.system.workspace.vo.InvitationHistoryResponseVO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/invitations")
public class WorkspaceInvitationController {

    private final WorkspaceInvitationService invitationService;

    public WorkspaceInvitationController(WorkspaceInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @GetMapping("/current")
    public Result<CurrentInvitationResponseVO> current(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(invitationService.current(currentUser.userId(), workspaceId));
    }

    @GetMapping
    public Result<List<InvitationHistoryResponseVO>> history(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(invitationService.history(currentUser.userId(), workspaceId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<CurrentInvitationResponseVO> create(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @Valid @RequestBody CreateInvitationRequestDTO request
    ) {
        return Result.success(invitationService.create(
                currentUser.userId(),
                workspaceId,
                request.currentInvitationId()
        ));
    }

    @DeleteMapping("/{invitationId}")
    public Result<Void> revoke(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long invitationId
    ) {
        invitationService.revoke(currentUser.userId(), workspaceId, invitationId);
        return Result.success(null);
    }
}
