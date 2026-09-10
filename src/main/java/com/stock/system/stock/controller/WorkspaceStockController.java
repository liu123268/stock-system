package com.stock.system.stock.controller;

import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.service.StockResearchService;
import com.stock.system.stock.service.StockCandlestickService;
import com.stock.system.stock.service.StockIntradayService;
import com.stock.system.stock.vo.AddWorkspaceStockResponseVO;
import com.stock.system.stock.vo.WorkspaceStockListResponseVO;
import com.stock.system.stock.vo.StockCandlestickSeriesVO;
import com.stock.system.stock.vo.StockIntradaySeriesVO;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/stocks")
public class WorkspaceStockController {

    private final StockResearchService stockResearchService;
    private final StockCandlestickService stockCandlestickService;
    private final StockIntradayService stockIntradayService;

    public WorkspaceStockController(
            StockResearchService stockResearchService,
            StockCandlestickService stockCandlestickService,
            StockIntradayService stockIntradayService
    ) {
        this.stockResearchService = stockResearchService;
        this.stockCandlestickService = stockCandlestickService;
        this.stockIntradayService = stockIntradayService;
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

    @GetMapping("/{workspaceStockId}/candlesticks")
    public Result<StockCandlestickSeriesVO> candlesticks(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(defaultValue = "1000") int count,
            @RequestParam(required = false) Instant before
    ) {
        return Result.success(stockCandlestickService.get(
                currentUser.userId(), workspaceId, workspaceStockId, period, count, before));
    }

    @GetMapping("/{workspaceStockId}/intraday")
    public Result<StockIntradaySeriesVO> intraday(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @RequestParam(required = false) LocalDate date
    ) {
        return Result.success(stockIntradayService.get(
                currentUser.userId(), workspaceId, workspaceStockId, date));
    }
}
