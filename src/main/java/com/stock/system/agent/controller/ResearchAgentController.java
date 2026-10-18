package com.stock.system.agent.controller;

import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.service.ResearchAgentService;
import com.stock.system.agent.service.ResearchAgentStreamService;
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
public class ResearchAgentController {
    private final ResearchAgentService service;
    private final ResearchAgentStreamService streams;
    public ResearchAgentController(ResearchAgentService service, ResearchAgentStreamService streams) {
        this.service = service; this.streams = streams;
    }

    @PostMapping(value = "/summaries/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter summarizeStream(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                      @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                      @Valid @RequestBody SummaryRequest request, HttpServletResponse response) {
        streamHeaders(response);
        return streams.open(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), request.requestId(),
                null, new ResearchSourceFilter(request.authorUserId(), request.dateFrom(), request.dateTo()));
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                @Valid @RequestBody QuestionRequest request, HttpServletResponse response) {
        streamHeaders(response);
        return streams.open(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), request.requestId(),
                request.question(), new ResearchSourceFilter(request.authorUserId(), request.dateFrom(), request.dateTo()));
    }

    @PostMapping("/requests/{requestId}/cancel")
    public Result<ResearchAgentStreamService.Cancellation> cancel(@AuthenticationPrincipal LoginUser user,
            @PathVariable long workspaceId, @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
            @PathVariable String requestId) {
        return Result.success(streams.cancel(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), requestId));
    }

    @GetMapping
    public Result<Session> restore(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                  @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                  @RequestParam(required = false) Long beforeRunId) {
        return Result.success(service.restore(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), beforeRunId));
    }
    @PostMapping("/summaries")
    public Result<Run> summarize(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                @Valid @RequestBody SummaryRequest request) {
        return Result.success(service.summarize(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId),
                request.requestId(), new ResearchSourceFilter(request.authorUserId(), request.dateFrom(), request.dateTo())));
    }
    @GetMapping("/runs/{runId}")
    public Result<Run> status(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                             @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                             @PathVariable long runId) {
        return Result.success(service.status(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), runId));
    }
    @PostMapping("/messages")
    public Result<Run> ask(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                          @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                          @Valid @RequestBody QuestionRequest request) {
        return Result.success(service.ask(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId),
                request.requestId(), request.question(), new ResearchSourceFilter(request.authorUserId(), request.dateFrom(), request.dateTo())));
    }
    @GetMapping("/requests/{requestId}")
    public Result<Run> requestStatus(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                                    @PathVariable long workspaceStockId, @PathVariable long researchCycleId,
                                    @PathVariable String requestId) {
        return Result.success(service.requestStatus(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId), requestId));
    }
    @DeleteMapping
    public Result<Void> clear(@AuthenticationPrincipal LoginUser user, @PathVariable long workspaceId,
                             @PathVariable long workspaceStockId, @PathVariable long researchCycleId) {
        service.clear(new Target(user.userId(), workspaceId, workspaceStockId, researchCycleId));
        return Result.success(null);
    }
    public record SummaryRequest(@NotBlank @Size(min = 36, max = 36) String requestId,
                                 Long authorUserId, LocalDate dateFrom, LocalDate dateTo) {}
    public record QuestionRequest(@NotBlank @Size(min = 36, max = 36) String requestId,
                                  @NotBlank @Size(max = 2000) String question,
                                  Long authorUserId, LocalDate dateFrom, LocalDate dateTo) {}
    private void streamHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
    }
}
