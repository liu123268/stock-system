package com.stock.system.stock.controller;

import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.service.StockResearchService;
import com.stock.system.stock.vo.AddWorkspaceStockResponseVO;
import com.stock.system.stock.vo.WorkspaceStockListResponseVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/stocks")
public class WorkspaceStockController {

    private final StockResearchService stockResearchService;

    public WorkspaceStockController(StockResearchService stockResearchService) {
        this.stockResearchService = stockResearchService;
    }

    @PostMapping
    public Result<AddWorkspaceStockResponseVO> add(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @Valid @RequestBody AddWorkspaceStockRequestDTO request
    ) {
        return Result.success(stockResearchService.add(currentUser.userId(), workspaceId, request));
    }

    @GetMapping
    public Result<List<WorkspaceStockListResponseVO>> list(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(stockResearchService.list(currentUser.userId(), workspaceId));
    }
}
