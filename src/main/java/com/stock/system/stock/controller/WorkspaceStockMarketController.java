package com.stock.system.stock.controller;

import com.stock.system.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "07 空间行情预览")
public class WorkspaceStockMarketController {

    private final StockMarketPreviewService previewService;

    public WorkspaceStockMarketController(StockMarketPreviewService previewService) {
        this.previewService = previewService;
    }

    @GetMapping("/{code}")
    @Operation(summary = "按股票代码预览行情", description = "仍需有效空间成员权限；不创建共享股票或研究周期。")
    public Result<StockMarketPreviewResponseVO> detail(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable String code
    ) {
        return Result.success(previewService.detail(currentUser.userId(), workspaceId, code));
    }

    @GetMapping("/{code}/candlesticks")
    @Operation(summary = "按股票代码预览 K 线")
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
    @Operation(summary = "按股票代码预览分时")
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
