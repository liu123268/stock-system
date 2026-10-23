package com.stock.system.agent.controller;

import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.service.ResearchReportService;
import com.stock.system.common.api.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.stock.system.security.LoginUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/research/cycles/{researchCycleId}/agent/reports")
@Tag(name = "08 研究助手")
public class ResearchReportController {
    private final ResearchReportService reports;
    public ResearchReportController(ResearchReportService reports) { this.reports = reports; }
    @GetMapping
    @Operation(summary = "读取当前研究对象可用报告目录", description = "仅展示已审核且符合当前股票范围的报告；不执行模型生成或资料导入。")
    public Result<ResearchReportService.Library> library(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                                       @PathVariable long workspaceStockId, @PathVariable long researchCycleId) {
        return Result.success(reports.library(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId)));
    }
}
