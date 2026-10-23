package com.stock.system.workspace.controller;

import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.workspace.dto.TransferOwnershipRequestDTO;
import com.stock.system.workspace.service.WorkspaceMemberService;
import com.stock.system.workspace.vo.WorkspaceDetailResponseVO;
import com.stock.system.workspace.vo.WorkspaceMemberResponseVO;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
@Tag(name = "03 空间成员")
public class WorkspaceMemberController {

    private final WorkspaceMemberService memberService;

    public WorkspaceMemberController(WorkspaceMemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/members")
    @Operation(summary = "列出空间有效成员")
    public Result<List<WorkspaceMemberResponseVO>> list(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(memberService.list(currentUser.userId(), workspaceId));
    }

    @PostMapping("/leave")
    @Operation(summary = "退出空间", description = "OWNER 必须先转让所有权。")
    public Result<Void> leave(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        memberService.leave(currentUser.userId(), workspaceId);
        return Result.success(null);
    }

    @DeleteMapping("/members/{userId}")
    @Operation(summary = "移除空间成员", description = "仅 OWNER 可操作。")
    public Result<Void> remove(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long userId
    ) {
        memberService.remove(currentUser.userId(), workspaceId, userId);
        return Result.success(null);
    }

    @PostMapping("/ownership-transfer")
    @Operation(summary = "转让空间所有权", description = "仅 OWNER 可操作，目标必须是账号正常的有效成员。")
    public Result<WorkspaceDetailResponseVO> transfer(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @Valid @RequestBody TransferOwnershipRequestDTO request
    ) {
        return Result.success(memberService.transfer(
                currentUser.userId(),
                workspaceId,
                request.targetUserId()
        ));
    }
}
