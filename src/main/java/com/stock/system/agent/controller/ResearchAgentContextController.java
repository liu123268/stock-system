package com.stock.system.agent.controller;

import com.stock.system.agent.service.ResearchMarketDataService;
import com.stock.system.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.stock.system.security.LoginUser;
import com.stock.system.stock.dto.ResearchSourceFilter;
import com.stock.system.stock.service.StockResearchSourceService;
import com.stock.system.stock.vo.ResearchCycleSourcesResponseVO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** 页面准备资料的只读入口；不创建会话、不调用模型或发生生成费用。 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/research/cycles/{researchCycleId}/agent")
@Tag(name = "08 研究助手")
public class ResearchAgentContextController {
    private final StockResearchSourceService sources;
    private final ResearchMarketDataService marketData;

    public ResearchAgentContextController(StockResearchSourceService sources, ResearchMarketDataService marketData) {
        this.sources = sources;
        this.marketData = marketData;
    }

    @GetMapping("/context")
    @Operation(summary = "准备助手的可见资料与行情", description = "不创建会话、不调用模型；行情不可用与资料范围按实际响应说明。")
    public Result<Context> context(@AuthenticationPrincipal LoginUser user, @PathVariable Long workspaceId,
                                   @PathVariable Long workspaceStockId, @PathVariable Long researchCycleId,
                                   @RequestParam(required = false) Long authorUserId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        ResearchCycleSourcesResponseVO material = sources.readCycle(user.userId(), workspaceId, workspaceStockId,
                researchCycleId, new ResearchSourceFilter(authorUserId, dateFrom, dateTo));
        ResearchMarketDataService.Quote quote = marketData.readLatestQuote(user.userId(), workspaceId, workspaceStockId);
        if (material.scope().coverage() == ResearchCycleSourcesResponseVO.Coverage.COMPLETE) {
            sources.revalidate(user.userId(), workspaceId, workspaceStockId, researchCycleId, material);
        } else {
            sources.verifyReadAccess(user.userId(), workspaceId, workspaceStockId, researchCycleId);
        }
        return Result.success(new Context(material, quote));
    }

    public record Context(ResearchCycleSourcesResponseVO materials, ResearchMarketDataService.Quote quote) {}
}
