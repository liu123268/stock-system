package com.stock.system.workspace.controller;

import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.workspace.dto.TransferOwnershipRequestDTO;
import com.stock.system.workspace.service.WorkspaceMemberService;
import com.stock.system.workspace.vo.WorkspaceDetailResponseVO;
import com.stock.system.workspace.vo.WorkspaceMemberResponseVO;
import jakarta.validation.Valid;
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
public class WorkspaceMemberController {

    private final WorkspaceMemberService memberService;

    public WorkspaceMemberController(WorkspaceMemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/members")
    public Result<List<WorkspaceMemberResponseVO>> list(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(memberService.list(currentUser.userId(), workspaceId));
    }

    @PostMapping("/leave")
    public Result<Void> leave(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        memberService.leave(currentUser.userId(), workspaceId);
        return Result.success(null);
    }

    @DeleteMapping("/members/{userId}")
    public Result<Void> remove(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long userId
    ) {
        memberService.remove(currentUser.userId(), workspaceId, userId);
        return Result.success(null);
    }

    @PostMapping("/ownership-transfer")
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
