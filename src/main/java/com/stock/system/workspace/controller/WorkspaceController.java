package com.stock.system.workspace.controller;

import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.workspace.dto.CreateWorkspaceRequestDTO;
import com.stock.system.workspace.dto.JoinWorkspaceRequestDTO;
import com.stock.system.workspace.service.WorkspaceInvitationService;
import com.stock.system.workspace.service.WorkspaceService;
import com.stock.system.workspace.vo.JoinWorkspaceResponseVO;
import com.stock.system.workspace.vo.WorkspaceDetailResponseVO;
import com.stock.system.workspace.vo.WorkspaceSummaryResponseVO;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceInvitationService invitationService;

    public WorkspaceController(
            WorkspaceService workspaceService,
            WorkspaceInvitationService invitationService
    ) {
        this.workspaceService = workspaceService;
        this.invitationService = invitationService;
    }

    @PostMapping("/join")
    public Result<JoinWorkspaceResponseVO> join(
            @AuthenticationPrincipal LoginUser currentUser,
            @Valid @RequestBody JoinWorkspaceRequestDTO request
    ) {
        return Result.success(invitationService.join(currentUser.userId(), request.inviteCode()));
    }

    @GetMapping
    public Result<List<WorkspaceSummaryResponseVO>> list(
            @AuthenticationPrincipal LoginUser currentUser
    ) {
        return Result.success(workspaceService.list(currentUser.userId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<WorkspaceDetailResponseVO> create(
            @AuthenticationPrincipal LoginUser currentUser,
            @Valid @RequestBody CreateWorkspaceRequestDTO request
    ) {
        return Result.success(workspaceService.create(currentUser.userId(), request.name()));
    }

    @GetMapping("/{workspaceId}")
    public Result<WorkspaceDetailResponseVO> detail(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(workspaceService.detail(currentUser.userId(), workspaceId));
    }

    @PostMapping("/{workspaceId}/archive")
    public Result<WorkspaceDetailResponseVO> archive(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(workspaceService.archive(currentUser.userId(), workspaceId));
    }

    @PostMapping("/{workspaceId}/restore")
    public Result<WorkspaceDetailResponseVO> restore(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(workspaceService.restore(currentUser.userId(), workspaceId));
    }
}
