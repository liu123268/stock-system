package com.stock.system.agent;

import com.stock.system.agent.service.ResearchMarketDataService;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResearchMarketDataServiceTest {
    private final WorkspaceService workspace = mock(WorkspaceService.class);
    private final WorkspaceStockMapper stocks = mock(WorkspaceStockMapper.class);
    private final MarketDataProvider provider = mock(MarketDataProvider.class);
    private final StockCandlestickService candles = mock(StockCandlestickService.class);
    private final ResearchMarketDataService service = new ResearchMarketDataService(workspace, stocks, provider, candles);
    private final LocalDate end = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
    private Stock stock;

    @BeforeEach
    void prepareBoundStock() {
        stock = new Stock();
        stock.setCode("TEST.HK"); stock.setCurrency("HKD"); stock.setName("自造行情股票");
        when(stocks.selectStockIdentity(1L, 2L)).thenReturn(stock);
    }

    @Test
    void quoteKeepsProviderNumbersTimeCurrencySourceAndSimulationFlag() {
        LocalDateTime time = end.atTime(16, 0);
        when(provider.getLatestQuote("TEST.HK")).thenReturn(Optional.of(new StockQuoteSnapshot(
                "TEST.HK", "测试", "HKEX", "HKD", "LISTED", new BigDecimal("12.30"), time, "FIXTURE", true)));
        var quote = service.readLatestQuote(10L, 1L, 2L);
        assertThat(quote.status()).isEqualTo("AVAILABLE");
        assertThat(quote.price()).isEqualByComparingTo("12.30");
        assertThat(quote.quoteTime()).isEqualTo(time);
        assertThat(quote.source()).isEqualTo("FIXTURE");
        assertThat(quote.simulated()).isTrue();
    }

    @Test
    void missingOrMismatchedQuoteDoesNotInventPrice() {
        when(provider.getLatestQuote("TEST.HK")).thenReturn(Optional.empty());
        assertThat(service.readLatestQuote(10L, 1L, 2L).price()).isNull();
        when(provider.getLatestQuote("TEST.HK")).thenReturn(Optional.of(new StockQuoteSnapshot(
                "OTHER.HK", "错误股票", "HKEX", "HKD", "LISTED", BigDecimal.TEN, end.atStartOfDay(), "FIXTURE", false)));
        assertThat(service.readLatestQuote(10L, 1L, 2L).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void authorizationFailureNeverBecomesQuoteUnavailableAndDoesNotReachProvider() {
        when(workspace.detail(10L, 1L)).thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));
        assertThatThrownBy(() -> service.readLatestQuote(10L, 1L, 2L))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(ErrorCode.WORKSPACE_ACCESS_DENIED));
        verifyNoInteractions(provider);
    }

    @Test
    void rangeUsesBoundDailyHistoryMinimumCountAndJavaNominalComparison() {
        when(candles.get(eq(10L), eq(1L), eq(2L), eq("day"), anyInt(), any(Instant.class)))
                .thenReturn(series("TEST.HK", "HKD", List.of(
                        point(end.plusDays(1), "999"), point(end, "12"),
                        point(end.minusDays(3), "10"), point(end.minusDays(4), "1"))));
        var prices = service.readDailyPrices(10L, 1L, 2L, end.minusDays(3), end);
        assertThat(prices.prices()).hasSize(2);
        assertThat(prices.actualFrom()).isEqualTo(end.minusDays(3));
        assertThat(prices.actualTo()).isEqualTo(end);
        assertThat(prices.nominalPriceDifference()).isEqualByComparingTo("2");
        assertThat(prices.nominalPriceChangePercent()).isEqualByComparingTo("20");
        assertThat(prices.coverageNote()).contains("实际交易日", "未复权");
        verify(candles).get(10L, 1L, 2L, "day", 20, end.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    @Test
    void invalidOrFutureOrOverNinetyDayInputIsRejectedBeforeUpstream() {
        for (LocalDate[] range : new LocalDate[][]{{end, end.minusDays(1)}, {end.minusDays(31), end},
                {end, end.plusDays(2)}, {null, end}}) {
            assertThatThrownBy(() -> service.readDailyPrices(10L, 1L, 2L, range[0], range[1]))
                    .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_REQUEST));
        }
        verifyNoInteractions(candles);
    }

    @Test
    void sparseOrNonPositivePricesDoNotBecomeMisleadingPercentages() {
        when(candles.get(eq(10L), eq(1L), eq(2L), eq("day"), anyInt(), any(Instant.class)))
                .thenReturn(series("TEST.HK", "HKD", List.of(point(end, "12"))));
        assertThat(service.readDailyPrices(10L, 1L, 2L, end.minusDays(2), end).nominalPriceDifference()).isNull();
        when(candles.get(eq(10L), eq(1L), eq(2L), eq("day"), anyInt(), any(Instant.class)))
                .thenReturn(series("TEST.HK", "HKD", List.of(point(end.minusDays(2), "0"), point(end, "12"))));
        var prices = service.readDailyPrices(10L, 1L, 2L, end.minusDays(2), end);
        assertThat(prices.nominalPriceChangePercent()).isNull();
        assertThat(prices.changeUnavailableReason()).isEqualTo("INVALID_CLOSE_PRICE");
    }

    @Test
    void sourceCurrencyOrStockMismatchAndDuplicateDayPreventNumericComparison() {
        for (StockCandlestickSeriesVO series : List.of(
                series("OTHER.HK", "HKD", List.of(point(end, "10"))),
                series("TEST.HK", "USD", List.of(point(end, "10"))),
                series("TEST.HK", "HKD", List.of(point(end, "10"), point(end, "12"))))) {
            when(candles.get(eq(10L), eq(1L), eq(2L), eq("day"), anyInt(), any(Instant.class))).thenReturn(series);
            assertThat(service.readDailyPrices(10L, 1L, 2L, end.minusDays(2), end).status()).isEqualTo("UNAVAILABLE");
        }
    }

    @Test
    void quoteAndDailyProviderErrorsAreControlledButPermissionErrorsPropagate() {
        when(provider.getLatestQuote("TEST.HK")).thenThrow(new IllegalStateException("provider-only-error"));
        assertThat(service.readLatestQuote(10L, 1L, 2L).status()).isEqualTo("UNAVAILABLE");
        when(candles.get(eq(10L), eq(1L), eq(2L), eq("day"), anyInt(), any(Instant.class)))
                .thenThrow(new BusinessException(ErrorCode.MARKET_KLINE_LIMIT_EXCEEDED));
        assertThat(service.readDailyPrices(10L, 1L, 2L, end.minusDays(2), end).changeUnavailableReason())
                .isEqualTo("MARKET_KLINE_LIMIT_EXCEEDED");
        when(candles.get(eq(10L), eq(1L), eq(2L), eq("day"), anyInt(), any(Instant.class)))
                .thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));
        assertThatThrownBy(() -> service.readDailyPrices(10L, 1L, 2L, end.minusDays(2), end))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(ErrorCode.WORKSPACE_ACCESS_DENIED));
    }

    private StockCandlestickVO point(LocalDate date, String close) {
        BigDecimal value = new BigDecimal(close);
        return new StockCandlestickVO(date.atTime(16, 0), value, value, value, value, 1L, value);
    }

    private StockCandlestickSeriesVO series(String code, String currency, List<StockCandlestickVO> points) {
        return new StockCandlestickSeriesVO(code, "自造股票", currency, "day", "none", "FIXTURE", true,
                end.atTime(17, 0), null, true, points);
    }
}
