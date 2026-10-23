package com.stock.system.workspace.controller;

import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.workspace.dto.CreateWorkspaceRequestDTO;
import com.stock.system.workspace.dto.JoinWorkspaceRequestDTO;
import com.stock.system.workspace.dto.RenameWorkspaceRequestDTO;
import com.stock.system.workspace.service.WorkspaceInvitationService;
import com.stock.system.workspace.service.WorkspaceOverviewService;
import com.stock.system.workspace.service.WorkspaceService;
import com.stock.system.workspace.vo.JoinWorkspaceResponseVO;
import com.stock.system.workspace.vo.WorkspaceDetailResponseVO;
import com.stock.system.workspace.vo.WorkspaceSummaryResponseVO;
import com.stock.system.workspace.vo.WorkspaceOverviewResponseVO;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
@Tag(name = "02 研究空间")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final WorkspaceInvitationService invitationService;
    private final WorkspaceOverviewService overviewService;

    public WorkspaceController(
            WorkspaceService workspaceService,
            WorkspaceInvitationService invitationService,
            WorkspaceOverviewService overviewService
    ) {
        this.workspaceService = workspaceService;
        this.invitationService = invitationService;
        this.overviewService = overviewService;
    }

    @PostMapping("/join")
    @Operation(summary = "使用邀请码加入空间")
    public Result<JoinWorkspaceResponseVO> join(
            @AuthenticationPrincipal LoginUser currentUser,
            @Valid @RequestBody JoinWorkspaceRequestDTO request
    ) {
        return Result.success(invitationService.join(currentUser.userId(), request.inviteCode()));
    }

    @GetMapping("/{workspaceId}/overview")
    @Operation(summary = "读取空间概览")
    public Result<WorkspaceOverviewResponseVO> overview(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(overviewService.get(currentUser.userId(), workspaceId));
    }

    @GetMapping
    @Operation(summary = "列出当前账号可见空间")
    public Result<List<WorkspaceSummaryResponseVO>> list(
            @AuthenticationPrincipal LoginUser currentUser
    ) {
        return Result.success(workspaceService.list(currentUser.userId()));
    }

    @PostMapping
    @Operation(summary = "创建研究空间")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<WorkspaceDetailResponseVO> create(
            @AuthenticationPrincipal LoginUser currentUser,
            @Valid @RequestBody CreateWorkspaceRequestDTO request
    ) {
        return Result.success(workspaceService.create(currentUser.userId(), request.name()));
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "读取空间详情")
    public Result<WorkspaceDetailResponseVO> detail(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(workspaceService.detail(currentUser.userId(), workspaceId));
    }

    @PatchMapping("/{workspaceId}")
    @Operation(summary = "修改空间名称")
    public Result<WorkspaceDetailResponseVO> rename(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @Valid @RequestBody RenameWorkspaceRequestDTO request
    ) {
        return Result.success(workspaceService.rename(currentUser.userId(), workspaceId, request.name()));
    }

    @PostMapping("/{workspaceId}/archive")
    @Operation(summary = "归档空间", description = "仅 OWNER 可操作。归档后研究内容只读。")
    public Result<WorkspaceDetailResponseVO> archive(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(workspaceService.archive(currentUser.userId(), workspaceId));
    }

    @PostMapping("/{workspaceId}/restore")
    @Operation(summary = "恢复空间", description = "OWNER 的恢复权限仍受归档来源约束。")
    public Result<WorkspaceDetailResponseVO> restore(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(workspaceService.restore(currentUser.userId(), workspaceId));
    }
}
