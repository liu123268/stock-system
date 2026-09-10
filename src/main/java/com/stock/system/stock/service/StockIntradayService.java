package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.market.MarketIntradayLimitExceededException;
import com.stock.system.stock.market.MarketIntradayProvider;
import com.stock.system.stock.market.MarketIntradayUnavailableException;
import com.stock.system.stock.market.MarketTradingSessions;
import com.stock.system.stock.vo.StockIntradayPointVO;
import com.stock.system.stock.vo.StockIntradaySeriesVO;
import com.stock.system.stock.vo.StockTradingSessionVO;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
public class StockIntradayService {

    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final WorkspaceService workspaceService;
    private final WorkspaceStockMapper workspaceStockMapper;
    private final MarketIntradayProvider intradayProvider;

    public StockIntradayService(
            WorkspaceService workspaceService,
            WorkspaceStockMapper workspaceStockMapper,
            MarketIntradayProvider intradayProvider
    ) {
        this.workspaceService = workspaceService;
        this.workspaceStockMapper = workspaceStockMapper;
        this.intradayProvider = intradayProvider;
    }

    public StockIntradaySeriesVO get(
            Long userId,
            Long workspaceId,
            Long workspaceStockId,
            LocalDate requestedDate
    ) {
        workspaceService.detail(userId, workspaceId);
        Stock stock = workspaceStockMapper.selectStockIdentity(workspaceId, workspaceStockId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        }
        ZoneId marketZone = MarketTradingSessions.zoneFor(stock.getCode());
        LocalDate tradeDate = requestedDate == null ? LocalDate.now(marketZone) : requestedDate;
        if (tradeDate.isAfter(LocalDate.now(marketZone))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        try {
            var series = intradayProvider.getIntraday(stock.getCode(), tradeDate);
            var points = series.points().stream()
                    .map(item -> new StockIntradayPointVO(
                            LocalDateTime.ofInstant(item.timestamp(), marketZone),
                            item.price(), item.averagePrice(), item.volume(), item.turnover()))
                    .toList();
            var tradingSessions = series.tradingSessions().stream()
                    .map(item -> new StockTradingSessionVO(item.start(), item.end()))
                    .toList();
            return new StockIntradaySeriesVO(
                    stock.getCode(), stock.getName(), stock.getCurrency(), series.tradeDate(),
                    series.marketTimeZone(), tradingSessions, series.referencePrice(),
                    series.updating(), series.source(), series.simulated(),
                    LocalDateTime.ofInstant(series.retrievedAt(), PROJECT_ZONE), points);
        } catch (MarketIntradayLimitExceededException exception) {
            throw new BusinessException(ErrorCode.MARKET_INTRADAY_HISTORY_LIMIT_EXCEEDED);
        } catch (MarketIntradayUnavailableException exception) {
            throw new BusinessException(ErrorCode.MARKET_INTRADAY_UNAVAILABLE);
        }
    }

}
