package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.dto.UpdateAttentionLevelRequestDTO;
import com.stock.system.stock.market.MarketDataProvider;
import com.stock.system.stock.market.StockQuoteSnapshot;
import com.stock.system.stock.vo.AddWorkspaceStockResponseVO;
import com.stock.system.stock.vo.WorkspaceStockListResponseVO;
import com.stock.system.stock.vo.UpdateAttentionLevelResponseVO;
import com.stock.system.stock.mapper.WorkspaceStockListRow;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class StockResearchService {

    private final MarketDataProvider marketDataProvider;
    private final StockResearchTransactionService transactionService;
    private final WorkspaceService workspaceService;
    private final WorkspaceStockMapper workspaceStockMapper;

    public StockResearchService(
            MarketDataProvider marketDataProvider,
            StockResearchTransactionService transactionService,
            WorkspaceService workspaceService,
            WorkspaceStockMapper workspaceStockMapper
    ) {
        this.marketDataProvider = marketDataProvider;
        this.transactionService = transactionService;
        this.workspaceService = workspaceService;
        this.workspaceStockMapper = workspaceStockMapper;
    }

    public AddWorkspaceStockResponseVO add(
            Long userId,
            Long workspaceId,
            AddWorkspaceStockRequestDTO request
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        StockQuoteSnapshot snapshot = marketDataProvider.getLatestQuote(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.MARKET_QUOTE_UNAVAILABLE));
        if ("DELISTED".equals(snapshot.listingStatus())) {
            throw new BusinessException(ErrorCode.STOCK_NOT_AVAILABLE);
        }
        if (!"LISTED".equals(snapshot.listingStatus())
                && !"SUSPENDED".equals(snapshot.listingStatus())) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_UNAVAILABLE);
        }
        return transactionService.add(userId, workspaceId, request, snapshot);
    }

    public List<WorkspaceStockListResponseVO> list(Long userId, Long workspaceId) {
        workspaceService.detail(userId, workspaceId);
        return workspaceStockMapper.selectActiveList(workspaceId).stream()
                .map(this::toListResponse)
                .toList();
    }

    public UpdateAttentionLevelResponseVO updateAttentionLevel(
            Long userId,
            Long workspaceId,
            Long workspaceStockId,
            UpdateAttentionLevelRequestDTO request
    ) {
        return transactionService.updateAttentionLevel(
                userId,
                workspaceId,
                workspaceStockId,
                request.researchCycleId(),
                request.attentionLevel()
        );
    }

    private WorkspaceStockListResponseVO toListResponse(WorkspaceStockListRow row) {
        boolean withdrawn = Boolean.TRUE.equals(row.getInitialOpinionWithdrawn());
        return new WorkspaceStockListResponseVO(
                row.getWorkspaceStockId(),
                row.getResearchCycleId(),
                row.getCode(),
                row.getName(),
                row.getExchange(),
                row.getCurrency(),
                row.getListingStatus(),
                row.getAttentionLevel(),
                row.getStartedByUserId(),
                row.getStartedByNickname(),
                row.getStartedAt(),
                row.getStartPrice(),
                row.getQuoteTime(),
                row.getQuoteSource(),
                Boolean.TRUE.equals(row.getSimulated()),
                row.getInitialOpinionId(),
                row.getAuthorUserId(),
                row.getAuthorNickname(),
                row.getStance(),
                withdrawn ? null : row.getReason(),
                withdrawn ? null : row.getObservationNote(),
                row.getHorizon(),
                withdrawn ? null : row.getTargetPrice(),
                withdrawn ? null : row.getStopLossPrice(),
                row.getOpinionCreatedAt(),
                withdrawn
        );
    }
}
