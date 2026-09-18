package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.dto.CreateStockOpinionRequestDTO;
import com.stock.system.stock.entity.ResearchCycle;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.entity.StockOpinion;
import com.stock.system.stock.mapper.EndedWorkspaceStockRow;
import com.stock.system.stock.mapper.ResearchCycleMapper;
import com.stock.system.stock.mapper.StockOpinionMapper;
import com.stock.system.stock.mapper.StockOpinionTimelineRow;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.vo.EndedWorkspaceStockResponseVO;
import com.stock.system.stock.vo.ResearchCycleDetailResponseVO;
import com.stock.system.stock.vo.ResearchCycleStateResponseVO;
import com.stock.system.stock.vo.StockOpinionResponseVO;
import com.stock.system.stock.vo.WorkspaceStockResearchResponseVO;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

@Service
public class StockResearchLifecycleService {

    private static final Duration WITHDRAW_WINDOW = Duration.ofMinutes(5);

    private final WorkspaceService workspaceService;
    private final WorkspaceStockMapper workspaceStockMapper;
    private final ResearchCycleMapper researchCycleMapper;
    private final StockOpinionMapper stockOpinionMapper;
    private final UserService userService;

    public StockResearchLifecycleService(
            WorkspaceService workspaceService,
            WorkspaceStockMapper workspaceStockMapper,
            ResearchCycleMapper researchCycleMapper,
            StockOpinionMapper stockOpinionMapper,
            UserService userService
    ) {
        this.workspaceService = workspaceService;
        this.workspaceStockMapper = workspaceStockMapper;
        this.researchCycleMapper = researchCycleMapper;
        this.stockOpinionMapper = stockOpinionMapper;
        this.userService = userService;
    }

    public List<EndedWorkspaceStockResponseVO> listEnded(Long userId, Long workspaceId) {
        workspaceService.detail(userId, workspaceId);
        return workspaceStockMapper.selectEndedList(workspaceId).stream()
                .map(this::toEndedResponse)
                .toList();
    }

    public WorkspaceStockResearchResponseVO detail(
            Long userId,
            Long workspaceId,
            Long workspaceStockId
    ) {
        var workspace = workspaceService.detail(userId, workspaceId);
        boolean workspaceActive = "ACTIVE".equals(workspace.status());
        Stock stock = requireStock(workspaceId, workspaceStockId);
        List<ResearchCycleDetailResponseVO> cycles = researchCycleMapper
                .selectAllForStock(workspaceId, workspaceStockId)
                .stream()
                .map(cycle -> toCycleResponse(cycle, userId, workspaceActive))
                .toList();
        return new WorkspaceStockResearchResponseVO(
                workspaceStockId,
                stock.getCode(),
                stock.getName(),
                stock.getExchange(),
                stock.getCurrency(),
                stock.getListingStatus(),
                cycles
        );
    }

    @Transactional
    public ResearchCycleStateResponseVO end(
            Long userId,
            Long workspaceId,
            Long workspaceStockId
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);
        requireStock(workspaceId, workspaceStockId);
        ResearchCycle cycle = requireActiveCycle(workspaceId, workspaceStockId);
        LocalDateTime endedAt = LocalDateTime.now();
        cycle.setStatus("ENDED");
        cycle.setEndedAt(endedAt);
        researchCycleMapper.updateById(cycle);
        return new ResearchCycleStateResponseVO(
                workspaceStockId,
                cycle.getId(),
                cycle.getStatus(),
                endedAt
        );
    }

    @Transactional
    public StockOpinionResponseVO createOpinion(
            Long userId,
            Long workspaceId,
            Long workspaceStockId,
            CreateStockOpinionRequestDTO request
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);
        requireStock(workspaceId, workspaceStockId);
        ResearchCycle cycle = requireActiveCycle(workspaceId, workspaceStockId);

        StockOpinion opinion = new StockOpinion();
        opinion.setResearchCycleId(cycle.getId());
        opinion.setAuthorUserId(userId);
        opinion.setType(request.type());
        opinion.setStance(request.stance());
        opinion.setReason(request.reason().trim());
        opinion.setObservationNote(normalizeOptionalText(request.observationNote()));
        opinion.setHorizon(request.horizon());
        opinion.setTargetPrice(request.targetPrice());
        opinion.setStopLossPrice(request.stopLossPrice());
        opinion.setCreatedAt(LocalDateTime.now());
        stockOpinionMapper.insert(opinion);

        AppUser author = userService.findById(userId);
        return toOpinionResponse(opinion, author.getNickname(), userId, true);
    }

    @Transactional
    public void withdrawOpinion(
            Long userId,
            Long workspaceId,
            Long workspaceStockId,
            Long opinionId
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);
        StockOpinion opinion = stockOpinionMapper.selectForUpdate(
                workspaceId,
                workspaceStockId,
                opinionId
        );
        if (opinion == null) {
            throw new BusinessException(ErrorCode.STOCK_OPINION_NOT_FOUND);
        }
        ResearchCycle cycle = researchCycleMapper.selectById(opinion.getResearchCycleId());
        if (!"ACTIVE".equals(cycle.getStatus())) {
            throw new BusinessException(ErrorCode.ACTIVE_RESEARCH_CYCLE_NOT_FOUND);
        }
        if (!opinion.getAuthorUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.STOCK_OPINION_ACCESS_DENIED);
        }
        if (opinion.getWithdrawnAt() != null) {
            throw new BusinessException(ErrorCode.STOCK_OPINION_ALREADY_WITHDRAWN);
        }
        LocalDateTime withdrawnAt = LocalDateTime.now();
        if (opinion.getCreatedAt().plus(WITHDRAW_WINDOW).isBefore(withdrawnAt)) {
            throw new BusinessException(ErrorCode.STOCK_OPINION_WITHDRAW_WINDOW_EXPIRED);
        }
        opinion.setWithdrawnAt(withdrawnAt);
        opinion.setWithdrawnByUserId(userId);
        stockOpinionMapper.updateById(opinion);
        if ("INITIAL".equals(opinion.getType())) {
            cycle.setStatus("CANCELLED");
            cycle.setEndedAt(withdrawnAt);
            researchCycleMapper.updateById(cycle);
        }
    }

    private Stock requireStock(Long workspaceId, Long workspaceStockId) {
        Stock stock = workspaceStockMapper.selectStockIdentity(workspaceId, workspaceStockId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        }
        return stock;
    }

    private ResearchCycle requireActiveCycle(Long workspaceId, Long workspaceStockId) {
        ResearchCycle cycle = researchCycleMapper.selectActiveForUpdate(
                workspaceId,
                workspaceStockId
        );
        if (cycle == null) {
            throw new BusinessException(ErrorCode.ACTIVE_RESEARCH_CYCLE_NOT_FOUND);
        }
        return cycle;
    }

    private ResearchCycleDetailResponseVO toCycleResponse(
            ResearchCycle cycle,
            Long userId,
            boolean workspaceActive
    ) {
        AppUser starter = userService.findById(cycle.getStartedByUserId());
        boolean active = "ACTIVE".equals(cycle.getStatus());
        List<StockOpinionResponseVO> opinions = stockOpinionMapper
                .selectTimeline(cycle.getId())
                .stream()
                .map(row -> toOpinionResponse(row, userId, active && workspaceActive))
                .toList();
        return new ResearchCycleDetailResponseVO(
                cycle.getId(),
                cycle.getStatus(),
                cycle.getAttentionLevel(),
                cycle.getStartedByUserId(),
                starter.getNickname(),
                cycle.getStartedAt(),
                cycle.getStartPrice(),
                cycle.getQuoteTime(),
                cycle.getQuoteSource(),
                Boolean.TRUE.equals(cycle.getIsSimulated()),
                cycle.getEndedAt(),
                opinions
        );
    }

    private StockOpinionResponseVO toOpinionResponse(
            StockOpinionTimelineRow row,
            Long userId,
            boolean active
    ) {
        boolean withdrawn = row.getWithdrawnAt() != null;
        return new StockOpinionResponseVO(
                row.getId(),
                row.getAuthorUserId(),
                row.getAuthorNickname(),
                row.getType(),
                row.getStance(),
                withdrawn ? null : row.getReason(),
                withdrawn ? null : row.getObservationNote(),
                row.getHorizon(),
                withdrawn ? null : row.getTargetPrice(),
                withdrawn ? null : row.getStopLossPrice(),
                row.getCreatedAt(),
                withdrawn,
                row.getWithdrawnAt(),
                canWithdraw(row.getCreatedAt(), row.getAuthorUserId(), userId, active, withdrawn)
        );
    }

    private StockOpinionResponseVO toOpinionResponse(
            StockOpinion opinion,
            String authorNickname,
            Long userId,
            boolean active
    ) {
        boolean withdrawn = opinion.getWithdrawnAt() != null;
        return new StockOpinionResponseVO(
                opinion.getId(),
                opinion.getAuthorUserId(),
                authorNickname,
                opinion.getType(),
                opinion.getStance(),
                withdrawn ? null : opinion.getReason(),
                withdrawn ? null : opinion.getObservationNote(),
                opinion.getHorizon(),
                withdrawn ? null : opinion.getTargetPrice(),
                withdrawn ? null : opinion.getStopLossPrice(),
                opinion.getCreatedAt(),
                withdrawn,
                opinion.getWithdrawnAt(),
                canWithdraw(opinion.getCreatedAt(), opinion.getAuthorUserId(), userId, active, withdrawn)
        );
    }

    private boolean canWithdraw(
            LocalDateTime createdAt,
            Long authorUserId,
            Long userId,
            boolean active,
            boolean withdrawn
    ) {
        return active
                && !withdrawn
                && authorUserId.equals(userId)
                && !createdAt.plus(WITHDRAW_WINDOW).isBefore(LocalDateTime.now());
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private EndedWorkspaceStockResponseVO toEndedResponse(EndedWorkspaceStockRow row) {
        return new EndedWorkspaceStockResponseVO(
                row.getWorkspaceStockId(),
                row.getCode(),
                row.getName(),
                row.getExchange(),
                row.getCurrency(),
                row.getResearchCycleId(),
                row.getStatus(),
                row.getStartedAt(),
                row.getEndedAt()
        );
    }
}
