package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.vo.StockCandlestickSeriesVO;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class StockCandlestickService {

    private final WorkspaceService workspaceService;
    private final WorkspaceStockMapper workspaceStockMapper;
    private final StockMarketSeriesService marketSeriesService;

    public StockCandlestickService(
            WorkspaceService workspaceService,
            WorkspaceStockMapper workspaceStockMapper,
            StockMarketSeriesService marketSeriesService
    ) {
        this.workspaceService = workspaceService;
        this.workspaceStockMapper = workspaceStockMapper;
        this.marketSeriesService = marketSeriesService;
    }

    public StockCandlestickSeriesVO get(
            Long userId,
            Long workspaceId,
            Long workspaceStockId,
            String rawPeriod,
            int count,
            Instant before
    ) {
        workspaceService.detail(userId, workspaceId);
        Stock stock = workspaceStockMapper.selectStockIdentity(workspaceId, workspaceStockId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        }
        return marketSeriesService.getCandlesticks(stock, rawPeriod, count, before);
    }
}
