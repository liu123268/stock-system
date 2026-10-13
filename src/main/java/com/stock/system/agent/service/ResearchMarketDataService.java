package com.stock.system.agent.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.market.MarketDataProvider;
import com.stock.system.stock.market.StockQuoteSnapshot;
import com.stock.system.stock.service.StockCandlestickService;
import com.stock.system.stock.vo.StockCandlestickSeriesVO;
import com.stock.system.stock.vo.StockCandlestickVO;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 研究助手使用的有界行情适配，复用现有供应商／缓存／SDK-CLI 选择。 */
@Service
public class ResearchMarketDataService {
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_DAILY_DAYS = 31;
    private final WorkspaceService workspaceService;
    private final WorkspaceStockMapper stockMapper;
    private final MarketDataProvider quoteProvider;
    private final StockCandlestickService candles;

    public ResearchMarketDataService(WorkspaceService workspaceService, WorkspaceStockMapper stockMapper,
                                     MarketDataProvider quoteProvider, StockCandlestickService candles) {
        this.workspaceService = workspaceService;
        this.stockMapper = stockMapper;
        this.quoteProvider = quoteProvider;
        this.candles = candles;
    }

    @Transactional(propagation = Propagation.NEVER)
    public Quote readLatestQuote(Long userId, Long workspaceId, Long workspaceStockId) {
        Stock stock = requireStock(userId, workspaceId, workspaceStockId);
        StockQuoteSnapshot snapshot;
        try {
            snapshot = quoteProvider.getLatestQuote(stock.getCode()).orElse(null);
        } catch (BusinessException failure) {
            if (failure.getErrorCode() != ErrorCode.MARKET_QUOTE_UNAVAILABLE) throw failure;
            return unavailableQuote(stock);
        } catch (RuntimeException upstreamFailure) {
            return unavailableQuote(stock);
        }
        if (snapshot == null || !Objects.equals(stock.getCode(), snapshot.code())
                || !Objects.equals(stock.getCurrency(), snapshot.currency())
                || snapshot.price() == null || snapshot.price().signum() <= 0
                || snapshot.quoteTime() == null || snapshot.source() == null || snapshot.source().isBlank()) {
            return unavailableQuote(stock);
        }
        return new Quote("AVAILABLE", stock.getCode(), snapshot.price(), snapshot.currency(),
                snapshot.quoteTime(), snapshot.source(), snapshot.simulated(), null);
    }

    @Transactional(propagation = Propagation.NEVER)
    public DailyPrices readDailyPrices(Long userId, Long workspaceId, Long workspaceStockId,
                                       LocalDate dateFrom, LocalDate dateTo) {
        Stock stock = requireStock(userId, workspaceId, workspaceStockId);
        if (dateFrom == null || dateTo == null || dateFrom.isAfter(dateTo)
                || dateTo.isAfter(LocalDate.now(PROJECT_ZONE))
                || ChronoUnit.DAYS.between(dateFrom, dateTo) >= MAX_DAILY_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        int count = Math.max(20, (int) ChronoUnit.DAYS.between(dateFrom, dateTo) + 8);
        StockCandlestickSeriesVO series;
        try {
            series = candles.get(userId, workspaceId, workspaceStockId, "day", count,
                    dateTo.plusDays(1).atStartOfDay(PROJECT_ZONE).toInstant());
        } catch (BusinessException failure) {
            if (failure.getErrorCode() != ErrorCode.MARKET_KLINE_UNAVAILABLE
                    && failure.getErrorCode() != ErrorCode.MARKET_KLINE_LIMIT_EXCEEDED) throw failure;
            return unavailablePrices(stock, dateFrom, dateTo, failure.getErrorCode().name());
        }
        if (series == null || !Objects.equals(stock.getCode(), series.code())
                || !Objects.equals(stock.getCurrency(), series.currency())
                || !"day".equals(series.period()) || !"none".equals(series.adjustment())
                || series.source() == null || series.source().isBlank() || series.candlesticks() == null) {
            return unavailablePrices(stock, dateFrom, dateTo, "SOURCE_MISMATCH");
        }
        if (series.candlesticks().stream().anyMatch(item -> item == null || item.time() == null)) {
            return unavailablePrices(stock, dateFrom, dateTo, "INVALID_DAILY_DATA");
        }
        List<StockCandlestickVO> points = series.candlesticks().stream()
                .filter(item -> !item.time().toLocalDate().isBefore(dateFrom)
                        && !item.time().toLocalDate().isAfter(dateTo))
                .sorted(Comparator.comparing(StockCandlestickVO::time)).toList();
        Set<LocalDate> dates = new HashSet<>();
        if (points.size() > MAX_DAILY_DAYS
                || points.stream().anyMatch(item -> !dates.add(item.time().toLocalDate()))) {
            return unavailablePrices(stock, dateFrom, dateTo, "INVALID_DAILY_DATA");
        }
        BigDecimal difference = null;
        BigDecimal percent = null;
        String changeUnavailable = "INSUFFICIENT_TRADING_DAYS";
        if (points.size() >= 2) {
            BigDecimal first = points.get(0).close();
            BigDecimal last = points.get(points.size() - 1).close();
            if (first == null || last == null || first.signum() <= 0 || last.signum() <= 0) {
                changeUnavailable = "INVALID_CLOSE_PRICE";
            } else {
                difference = last.subtract(first);
                percent = difference.multiply(BigDecimal.valueOf(100)).divide(first, 4, RoundingMode.HALF_UP);
                changeUnavailable = null;
            }
        }
        return new DailyPrices(points.isEmpty() ? "NO_DATA" : "AVAILABLE", stock.getCode(), series.currency(),
                dateFrom, dateTo, points.isEmpty() ? null : points.get(0).time().toLocalDate(),
                points.isEmpty() ? null : points.get(points.size() - 1).time().toLocalDate(),
                series.source(), series.simulated(), series.retrievedAt(), series.adjustment(), points,
                difference, percent, changeUnavailable,
                "仅包含本次取得的实际交易日；名义收盘价变化未复权，不代表投资回报。");
    }

    private Stock requireStock(Long userId, Long workspaceId, Long workspaceStockId) {
        if (workspaceId == null || workspaceId <= 0 || workspaceStockId == null || workspaceStockId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        workspaceService.detail(userId, workspaceId);
        Stock stock = stockMapper.selectStockIdentity(workspaceId, workspaceStockId);
        if (stock == null) throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        return stock;
    }

    private Quote unavailableQuote(Stock stock) {
        return new Quote("UNAVAILABLE", stock.getCode(), null, stock.getCurrency(), null, null, false,
                "当前报价暂不可用，仍可整理已有观点。");
    }

    private DailyPrices unavailablePrices(Stock stock, LocalDate from, LocalDate to, String reason) {
        return new DailyPrices("UNAVAILABLE", stock.getCode(), stock.getCurrency(), from, to, null, null,
                null, false, null, "none", List.of(), null, null, reason,
                "本次没有取得该范围的可核对日 K 数据。");
    }

    public record Quote(String status, String code, BigDecimal price, String currency,
                        LocalDateTime quoteTime, String source, boolean simulated, String unavailableReason) {}
    public record DailyPrices(String status, String code, String currency, LocalDate requestedFrom,
                              LocalDate requestedTo, LocalDate actualFrom, LocalDate actualTo,
                              String source, boolean simulated, LocalDateTime retrievedAt, String adjustment,
                              List<StockCandlestickVO> prices, BigDecimal nominalPriceDifference,
                              BigDecimal nominalPriceChangePercent, String changeUnavailableReason,
                              String coverageNote) {}
}
