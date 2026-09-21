package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.market.CandlestickPeriod;
import com.stock.system.stock.market.MarketCandlestickLimitExceededException;
import com.stock.system.stock.market.MarketCandlestickProvider;
import com.stock.system.stock.market.MarketCandlestickUnavailableException;
import com.stock.system.stock.market.MarketIntradayHistoryRangeExceededException;
import com.stock.system.stock.market.MarketIntradayLimitExceededException;
import com.stock.system.stock.market.MarketIntradayNonTradingDayException;
import com.stock.system.stock.market.MarketIntradayProvider;
import com.stock.system.stock.market.MarketIntradayUnavailableException;
import com.stock.system.stock.market.MarketTradingSessions;
import com.stock.system.stock.vo.StockCandlestickSeriesVO;
import com.stock.system.stock.vo.StockCandlestickVO;
import com.stock.system.stock.vo.StockIntradayPointVO;
import com.stock.system.stock.vo.StockIntradaySeriesVO;
import com.stock.system.stock.vo.StockTradingSessionVO;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;

@Service
public class StockMarketSeriesService {

    private static final int MIN_COUNT = 20;
    private static final int MAX_COUNT = 1000;
    private static final ZoneId PROJECT_ZONE = ZoneId.of("Asia/Shanghai");

    private final MarketCandlestickProvider candlestickProvider;
    private final MarketIntradayProvider intradayProvider;

    public StockMarketSeriesService(
            MarketCandlestickProvider candlestickProvider,
            MarketIntradayProvider intradayProvider
    ) {
        this.candlestickProvider = candlestickProvider;
        this.intradayProvider = intradayProvider;
    }

    public StockCandlestickSeriesVO getCandlesticks(
            Stock stock,
            String rawPeriod,
            int count,
            Instant before
    ) {
        if (count < MIN_COUNT || count > MAX_COUNT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        CandlestickPeriod period;
        try {
            period = CandlestickPeriod.fromApiValue(rawPeriod);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
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

    public StockIntradaySeriesVO getIntraday(Stock stock, LocalDate requestedDate) {
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
        } catch (MarketIntradayHistoryRangeExceededException exception) {
            throw new BusinessException(
                    ErrorCode.MARKET_INTRADAY_HISTORY_RANGE_EXCEEDED,
                    "当前账户的历史分钟行情最早可查看至 "
                            + exception.getEarliestAvailableDate()
                            + "，请选择该日期或之后的交易日"
            );
        } catch (MarketIntradayNonTradingDayException exception) {
            throw new BusinessException(ErrorCode.MARKET_INTRADAY_NON_TRADING_DAY);
        } catch (MarketIntradayUnavailableException exception) {
            throw new BusinessException(ErrorCode.MARKET_INTRADAY_UNAVAILABLE);
        }
    }
}
