package com.stock.system.stock.controller;

import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.dto.UpdateAttentionLevelRequestDTO;
import com.stock.system.stock.dto.CreateStockOpinionRequestDTO;
import com.stock.system.stock.dto.ResearchCycleCommandRequestDTO;
import com.stock.system.stock.service.StockResearchService;
import com.stock.system.stock.service.StockCandlestickService;
import com.stock.system.stock.service.StockIntradayService;
import com.stock.system.stock.service.StockResearchLifecycleService;
import com.stock.system.stock.service.StockResearchSourceService;
import com.stock.system.stock.vo.AddWorkspaceStockResponseVO;
import com.stock.system.stock.vo.WorkspaceStockListResponseVO;
import com.stock.system.stock.vo.StockCandlestickSeriesVO;
import com.stock.system.stock.vo.StockIntradaySeriesVO;
import com.stock.system.stock.vo.UpdateAttentionLevelResponseVO;
import com.stock.system.stock.vo.EndedWorkspaceStockResponseVO;
import com.stock.system.stock.vo.ResearchCycleStateResponseVO;
import com.stock.system.stock.vo.ResearchTimelinePageResponseVO;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import com.stock.system.stock.vo.StockOpinionResponseVO;
import com.stock.system.stock.vo.WorkspaceStockResearchResponseVO;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final StockResearchLifecycleService lifecycleService;
    private final StockResearchSourceService sourceService;

    public WorkspaceStockController(
            StockResearchService stockResearchService,
            StockCandlestickService stockCandlestickService,
            StockIntradayService stockIntradayService,
            StockResearchLifecycleService lifecycleService,
            StockResearchSourceService sourceService
    ) {
        this.stockResearchService = stockResearchService;
        this.stockCandlestickService = stockCandlestickService;
        this.stockIntradayService = stockIntradayService;
        this.lifecycleService = lifecycleService;
        this.sourceService = sourceService;
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

    @GetMapping("/ended")
    public Result<List<EndedWorkspaceStockResponseVO>> listEnded(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(lifecycleService.listEnded(currentUser.userId(), workspaceId));
    }

    @GetMapping("/{workspaceStockId}/research")
    public Result<WorkspaceStockResearchResponseVO> researchDetail(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId
    ) {
        return Result.success(lifecycleService.detail(
                currentUser.userId(), workspaceId, workspaceStockId));
    }

    @GetMapping("/{workspaceStockId}/research/timeline")
    public Result<ResearchTimelinePageResponseVO> researchTimeline(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String stance,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cursor
    ) {
        return Result.success(lifecycleService.timeline(
                currentUser.userId(), workspaceId, workspaceStockId,
                author, stance, type, cursor));
    }

    @GetMapping("/{workspaceStockId}/research/cycles/{researchCycleId}/sources")
    public Result<ResearchCycleSourcesResponseVO> researchSources(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @PathVariable Long researchCycleId
    ) {
        return Result.success(sourceService.readCycle(
                currentUser.userId(), workspaceId, workspaceStockId, researchCycleId));
    }

    @GetMapping("/{workspaceStockId}/research/cycles/{researchCycleId}/opinions/{opinionId}")
    public Result<ResearchCycleSourcesResponseVO.OpinionSource> researchOpinionSource(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @PathVariable Long researchCycleId,
            @PathVariable Long opinionId
    ) {
        return Result.success(sourceService.readOpinion(
                currentUser.userId(), workspaceId, workspaceStockId, researchCycleId, opinionId));
    }

    @PostMapping("/{workspaceStockId}/end")
    public Result<ResearchCycleStateResponseVO> end(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @Valid @RequestBody ResearchCycleCommandRequestDTO request
    ) {
        return Result.success(lifecycleService.end(
                currentUser.userId(), workspaceId, workspaceStockId, request.researchCycleId()));
    }

    @PostMapping("/{workspaceStockId}/opinions")
    public Result<StockOpinionResponseVO> createOpinion(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @Valid @RequestBody CreateStockOpinionRequestDTO request
    ) {
        return Result.success(lifecycleService.createOpinion(
                currentUser.userId(), workspaceId, workspaceStockId, request));
    }

    @DeleteMapping("/{workspaceStockId}/opinions/{opinionId}")
    public Result<Void> withdrawOpinion(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @PathVariable Long opinionId
    ) {
        lifecycleService.withdrawOpinion(
                currentUser.userId(), workspaceId, workspaceStockId, opinionId);
        return Result.success(null);
    }

    @PatchMapping("/{workspaceStockId}/attention")
    public Result<UpdateAttentionLevelResponseVO> updateAttentionLevel(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @Valid @RequestBody UpdateAttentionLevelRequestDTO request
    ) {
        return Result.success(stockResearchService.updateAttentionLevel(
                currentUser.userId(), workspaceId, workspaceStockId, request));
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
