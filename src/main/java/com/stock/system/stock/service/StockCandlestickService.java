package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.market.CandlestickPeriod;
import com.stock.system.stock.market.MarketCandlestickProvider;
import com.stock.system.stock.market.MarketCandlestickLimitExceededException;
import com.stock.system.stock.market.MarketCandlestickUnavailableException;
import com.stock.system.stock.vo.StockCandlestickSeriesVO;
import com.stock.system.stock.vo.StockCandlestickVO;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;

@Service
public class StockCandlestickService {

    private static final int MIN_COUNT = 20;
    private static final int MAX_COUNT = 1000;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final WorkspaceService workspaceService;
    private final WorkspaceStockMapper workspaceStockMapper;
    private final MarketCandlestickProvider candlestickProvider;

    public StockCandlestickService(
            WorkspaceService workspaceService,
            WorkspaceStockMapper workspaceStockMapper,
            MarketCandlestickProvider candlestickProvider
    ) {
        this.workspaceService = workspaceService;
        this.workspaceStockMapper = workspaceStockMapper;
        this.candlestickProvider = candlestickProvider;
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
        if (count < MIN_COUNT || count > MAX_COUNT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        CandlestickPeriod period;
        try {
            period = CandlestickPeriod.fromApiValue(rawPeriod);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        Stock stock = workspaceStockMapper.selectStockIdentity(workspaceId, workspaceStockId);
        if (stock == null) {
            throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        }

        try {
            var series = before == null
                    ? candlestickProvider.getCandlesticks(stock.getCode(), period, count)
                    : candlestickProvider.getCandlesticksBefore(
                            stock.getCode(), period, count, before);
            var items = series.candlesticks().stream()
                    .sorted(Comparator.comparing(item -> item.timestamp()))
                    .map(item -> new StockCandlestickVO(
                            LocalDateTime.ofInstant(item.timestamp(), PROJECT_ZONE),
                            item.open(), item.high(), item.low(), item.close(), item.volume(),
                            item.turnover()))
                    .toList();
            Instant nextBefore = series.candlesticks().stream()
                    .map(item -> item.timestamp())
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            return new StockCandlestickSeriesVO(
                    stock.getCode(), stock.getName(), stock.getCurrency(), period.apiValue(),
                    "none", series.source(), series.simulated(),
                    LocalDateTime.ofInstant(series.retrievedAt(), PROJECT_ZONE),
                    nextBefore, series.hasMore(), items);
        } catch (MarketCandlestickLimitExceededException exception) {
            throw new BusinessException(ErrorCode.MARKET_KLINE_LIMIT_EXCEEDED);
        } catch (MarketCandlestickUnavailableException exception) {
            throw new BusinessException(ErrorCode.MARKET_KLINE_UNAVAILABLE);
        }
    }
}
