package com.stock.system.agent.controller;

import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.service.ResearchAgentService;
import com.stock.system.agent.service.ResearchAgentStreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.stock.system.agent.vo.ResearchAgentResponseVO.Run;
import com.stock.system.agent.vo.ResearchAgentResponseVO.Session;
import com.stock.system.common.api.Result;
import com.stock.system.security.LoginUser;
import com.stock.system.stock.dto.ResearchSourceFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/research/cycles/{researchCycleId}/agent")
@Tag(name = "08 研究助手", description = "记录按当前账号、空间股票和研究周期隔离；生成需启用模型且预算充足。")
public class ResearchAgentController {
    private final ResearchAgentService service;
    private final ResearchAgentStreamService streams;
    public ResearchAgentController(ResearchAgentService service, ResearchAgentStreamService streams) {
        this.service = service; this.streams = streams;
    }

    @PostMapping(value = "/summaries/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式梳理周期观点")
    public SseEmitter summarizeStream(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                      @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                      @Valid @RequestBody SummaryRequest request, HttpServletResponse response) {
        streamHeaders(response);
        return streams.open(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), request.requestId(),
                null, new ResearchSourceFilter(request.authorUserId(), request.dateFrom(), request.dateTo()));
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式追问研究助手")
    public SseEmitter askStream(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                @Valid @RequestBody QuestionRequest request, HttpServletResponse response) {
        streamHeaders(response);
        return streams.open(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), request.requestId(),
                request.question(), new ResearchSourceFilter(request.authorUserId(), request.dateFrom(), request.dateTo()));
    }

    @PostMapping("/requests/{requestId}/cancel")
    @Operation(summary = "停止本人的指定生成请求")
    public Result<ResearchAgentStreamService.Cancellation> cancel(@AuthenticationPrincipal LoginUser user,
            @PathVariable long workspaceId, @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
            @PathVariable String requestId) {
        return Result.success(streams.cancel(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), requestId));
    }

    @GetMapping
    @Operation(summary = "恢复本人的助手会话", description = "beforeRunId 不传时读最近记录，传入时向前翻页。")
    public Result<Session> restore(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                  @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                  @RequestParam(required = false) Long beforeRunId) {
        return Result.success(service.restore(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), beforeRunId));
    }
    @PostMapping("/summaries")
    @Operation(summary = "同步梳理周期观点", description = "等待生成完成后返回；可能消耗已配置模型预算。requestId 使用 UUID，同一请求的重试须保持内容和标识一致。")
    public Result<Run> summarize(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                @Valid @RequestBody SummaryRequest request) {
        return Result.success(service.summarize(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId),
                request.requestId(), new ResearchSourceFilter(request.authorUserId(), request.dateFrom(), request.dateTo())));
    }
    @GetMapping("/runs/{runId}")
    @Operation(summary = "查询本人的指定运行")
    public Result<Run> status(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                             @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                             @PathVariable long runId) {
        return Result.success(service.status(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), runId));
    }
    @PostMapping("/messages")
    @Operation(summary = "同步追问研究助手", description = "等待生成完成后返回；可能消耗已配置模型预算。requestId 使用 UUID，同一请求的重试须保持内容和标识一致。")
    public Result<Run> ask(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                          @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                          @Valid @RequestBody QuestionRequest request) {
        return Result.success(service.ask(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId),
                request.requestId(), request.question(), new ResearchSourceFilter(request.authorUserId(), request.dateFrom(), request.dateTo())));
    }
    @GetMapping("/requests/{requestId}")
    @Operation(summary = "按请求标识恢复本人运行状态")
    public Result<Run> requestStatus(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                    @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                    @PathVariable String requestId) {
        return Result.success(service.requestStatus(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), requestId));
    }
    @DeleteMapping
    @Operation(summary = "清空本人在当前对象下的助手记录", description = "不清除其他账号的记录，也不删除成员观点。")
    public Result<Void> clear(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                             @PathVariable long workspaceStockId, @PathVariable long researchCycleId) {
        service.clear(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId));
        return Result.success(null);
    }
    public record SummaryRequest(@Schema(format = "uuid", description = "同一请求重试时保持不变，不能复用于不同内容。",
                                         example = "00000000-0000-4000-8000-000000000001")
                                 @NotBlank @Size(min = 36, max = 36) String requestId,
                                 Long authorUserId, LocalDate dateFrom, LocalDate dateTo) {}
    public record QuestionRequest(@Schema(format = "uuid", description = "同一请求重试时保持不变，不能复用于不同内容。",
                                          example = "00000000-0000-4000-8000-000000000002")
                                  @NotBlank @Size(min = 36, max = 36) String requestId,
                                  @NotBlank @Size(max = 2000) String question,
                                  Long authorUserId, LocalDate dateFrom, LocalDate dateTo) {}
    private void streamHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
    }
}
