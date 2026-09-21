package com.stock.system.stock.controller;

import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.stock.service.StockMarketPreviewService;
import com.stock.system.stock.vo.StockCandlestickSeriesVO;
import com.stock.system.stock.vo.StockIntradaySeriesVO;
import com.stock.system.stock.vo.StockMarketPreviewResponseVO;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/market/stocks")
public class WorkspaceStockMarketController {

    private final StockMarketPreviewService previewService;

    public WorkspaceStockMarketController(StockMarketPreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/{code}")
    public Result<StockMarketPreviewResponseVO> detail(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable String code
    ) {
        return Result.success(previewService.detail(currentUser.userId(), workspaceId, code));
    }

    @GetMapping("/{code}/candlesticks")
    public Result<StockCandlestickSeriesVO> candlesticks(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable String code,
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(defaultValue = "1000") int count,
            @RequestParam(required = false) Instant before
    ) {
        return Result.success(previewService.candlesticks(
                currentUser.userId(), workspaceId, code, period, count, before));
    }

    @GetMapping("/{code}/intraday")
    public Result<StockIntradaySeriesVO> intraday(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable String code,
            @RequestParam(required = false) LocalDate date
    ) {
        return Result.success(previewService.intraday(
                currentUser.userId(), workspaceId, code, date));
    }
}
