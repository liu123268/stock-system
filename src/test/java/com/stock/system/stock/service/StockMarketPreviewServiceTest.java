package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.StockMapper;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.market.MarketDataProvider;
import com.stock.system.stock.market.StockQuoteSnapshot;
import com.stock.system.stock.vo.StockCandlestickSeriesVO;
import com.stock.system.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockMarketPreviewServiceTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final StockMapper stockMapper = mock(StockMapper.class);
    private final WorkspaceStockMapper workspaceStockMapper = mock(WorkspaceStockMapper.class);
    private final MarketDataProvider marketDataProvider = mock(MarketDataProvider.class);
    private final StockMarketSeriesService marketSeriesService = mock(StockMarketSeriesService.class);

    @Test
    void membershipFailurePrecedesCodeValidationAndAllMarketAccess() {
        when(workspaceService.detail(7L, 11L))
                .thenThrow(new BusinessException(ErrorCode.WORKSPACE_NOT_FOUND));
        StockMarketPreviewService service = service("longbridge");

        assertWorkspaceNotFound(() -> service.detail(7L, 11L, "not-a-code"));
        assertWorkspaceNotFound(() -> service.candlesticks(
                7L, 11L, "not-a-code", "day", 120, null));
        assertWorkspaceNotFound(() -> service.intraday(
                7L, 11L, "not-a-code", LocalDate.of(2026, 9, 4)));

        verifyNoInteractions(stockMapper, workspaceStockMapper, marketDataProvider,
                marketSeriesService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "DELISTED"})
    void trustedDirectoryStatusDoesNotBlockHistoricalCandlesticks(String listingStatus) {
        Stock stock = directoryStock(listingStatus);
        StockCandlestickSeriesVO expected = new StockCandlestickSeriesVO(
                stock.getCode(), stock.getName(), stock.getCurrency(), "day", "none",
                "LONGBRIDGE_OPENAPI", false, LocalDateTime.of(2026, 9, 21, 12, 0),
                null, false, List.of());
        when(stockMapper.selectOne(any())).thenReturn(stock);
        when(marketSeriesService.getCandlesticks(stock, "day", 120, null))
                .thenReturn(expected);

        StockCandlestickSeriesVO result = service("longbridge").candlesticks(
                7L, 11L, "600519.sh", "day", 120, null);

        assertThat(result).isSameAs(expected);
        verify(workspaceService).detail(7L, 11L);
        verify(marketSeriesService).getCandlesticks(stock, "day", 120, null);
    }

    @Test
    void rejectsCodeOutsideTheCurrentDirectoryBeforeCallingMarketSeries() {
        when(stockMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service("directory").candlesticks(
                7L, 11L, "000000.SH", "day", 120, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.STOCK_NOT_FOUND));

        verify(workspaceService).detail(7L, 11L);
        verify(marketSeriesService, never())
                .getCandlesticks(any(), any(), anyInt(), any());
    }

    @Test
    void mockCandidateCanBePreviewedWithoutPersistedStockIdentity() {
        StockQuoteSnapshot snapshot = new StockQuoteSnapshot(
                "600519.SH", "贵州茅台", "SSE", "CNY", "LISTED",
                new BigDecimal("1488.8800"), LocalDateTime.of(2026, 9, 4, 15, 0),
                "LOCAL_MOCK", true);
        when(marketDataProvider.getLatestQuote("600519.SH")).thenReturn(Optional.of(snapshot));
        when(stockMapper.selectOne(any())).thenReturn(null);

        var result = service("mock").detail(7L, 11L, "600519.sh");

        assertThat(result.code()).isEqualTo("600519.SH");
        assertThat(result.price()).isEqualByComparingTo("1488.8800");
        assertThat(result.trackingState()).isEqualTo("NOT_TRACKED");
        assertThat(result.workspaceStockId()).isNull();
        assertThat(result.activeResearchCycleId()).isNull();
        verify(workspaceStockMapper, never()).selectTrackingState(any(), any());
    }

    private StockMarketPreviewService service(String mode) {
        return new StockMarketPreviewService(
                workspaceService,
                stockMapper,
                workspaceStockMapper,
                marketDataProvider,
                marketSeriesService,
                mode
        );
    }

    private void assertWorkspaceNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND));
    }

    private Stock directoryStock(String listingStatus) {
        Stock stock = new Stock();
        stock.setId(101L);
        stock.setCode("600519.SH");
        stock.setName("贵州茅台");
        stock.setExchange("SSE");
        stock.setCurrency("CNY");
        stock.setListingStatus(listingStatus);
        stock.setSource("SSE_DIRECTORY");
        return stock;
    }
}
