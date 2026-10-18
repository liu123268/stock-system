package com.stock.system.agent.controller;

import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.service.ResearchReportService;
import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/research/cycles/{researchCycleId}/agent/reports")
public class ResearchReportController {
    private final ResearchReportService reports;
    public ResearchReportController(ResearchReportService reports) { this.reports = reports; }
    @GetMapping
    public Result<ResearchReportService.Library> library(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                                       @PathVariable long workspaceStockId, @PathVariable long researchCycleId) {
        return Result.success(reports.library(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId)));
    }
}
