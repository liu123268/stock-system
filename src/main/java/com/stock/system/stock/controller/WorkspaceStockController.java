package com.stock.system.stock.controller;

import com.stock.system.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.stock.system.security.LoginUser;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.dto.UpdateAttentionLevelRequestDTO;
import com.stock.system.stock.dto.CreateStockOpinionRequestDTO;
import com.stock.system.stock.dto.ResearchCycleCommandRequestDTO;
import com.stock.system.stock.dto.ResearchSourceFilter;
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
import org.springframework.format.annotation.DateTimeFormat;

import java.util.List;
import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/stocks")
@Tag(name = "06 共享股票与研究")
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
    @Operation(summary = "关注股票或重新开始研究", description = "需要有效空间成员、可写空间及可信行情；重复关注按业务规则处理。")
    public Result<AddWorkspaceStockResponseVO> add(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @Valid @RequestBody AddWorkspaceStockRequestDTO request
    ) {
        return Result.success(stockResearchService.add(currentUser.userId(), workspaceId, request));
    }

    @GetMapping
    @Operation(summary = "列出正在研究的共享股票")
    public Result<List<WorkspaceStockListResponseVO>> list(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(stockResearchService.list(currentUser.userId(), workspaceId));
    }

    @GetMapping("/ended")
    @Operation(summary = "列出已结束研究的股票")
    public Result<List<EndedWorkspaceStockResponseVO>> listEnded(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId
    ) {
        return Result.success(lifecycleService.listEnded(currentUser.userId(), workspaceId));
    }

    @GetMapping("/{workspaceStockId}/research")
    @Operation(summary = "读取股票研究详情与周期")
    public Result<WorkspaceStockResearchResponseVO> researchDetail(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId
    ) {
        return Result.success(lifecycleService.detail(
                currentUser.userId(), workspaceId, workspaceStockId));
    }

    @GetMapping("/{workspaceStockId}/research/timeline")
    @Operation(summary = "按条件分页读取研究时间线", description = "cursor 是服务端游标，下一页使用上次响应的游标；筛选条件改变后重新开始。")
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
    @Operation(summary = "读取研究周期的可见观点资料")
    public Result<ResearchCycleSourcesResponseVO> researchSources(
            @AuthenticationPrincipal LoginUser currentUser,
            @PathVariable Long workspaceId,
            @PathVariable Long workspaceStockId,
            @PathVariable Long researchCycleId,
            @RequestParam(required = false) Long authorUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo
    ) {
        return Result.success(sourceService.readCycle(
                currentUser.userId(), workspaceId, workspaceStockId, researchCycleId,
                new ResearchSourceFilter(authorUserId, dateFrom, dateTo)));
    }

    @GetMapping("/{workspaceStockId}/research/cycles/{researchCycleId}/opinions/{opinionId}")
    @Operation(summary = "读取周期内某条可见观点原文")
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
    @Operation(summary = "结束当前研究周期", description = "请求须携带当前研究周期标识，以拒绝过期页面操作。")
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
    @Operation(summary = "在当前研究周期发表观点")
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
    @Operation(summary = "撤回自己的观点", description = "仅作者可在发表后五分钟内撤回；保留历史审计关系。")
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
    @Operation(summary = "更新共享股票关注等级")
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
    @Operation(summary = "读取共享股票 K 线", description = "before 使用 ISO-8601 时间，边界严格早于该时刻；不传时读取最近数据。")
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
    @Operation(summary = "读取共享股票分时", description = "date 不传时读当天，传入时读指定交易日；历史分钟数据受供应商账户范围限制。")
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
