package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.market.MarketDataProvider;
import com.stock.system.stock.market.StockQuoteSnapshot;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.vo.AddWorkspaceStockResponseVO;
import com.stock.system.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockResearchServiceTest {

    private final MarketDataProvider provider = mock(MarketDataProvider.class);
    private final StockResearchTransactionService transactionService =
            mock(StockResearchTransactionService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final StockResearchService service = new StockResearchService(
            provider, transactionService, workspaceService, mock(WorkspaceStockMapper.class));
    private final AddWorkspaceStockRequestDTO request = new AddWorkspaceStockRequestDTO(
            "600519.SH", "BULLISH", "长期品牌优势", null, "MEDIUM", null, null);

    @Test
    void rejectsUnauthorizedRequestBeforeCallingMarketProvider() {
        BusinessException denied = new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED);
        doThrow(denied).when(workspaceService).requireActiveMemberForUpdate(7L, 12L);

        assertThatThrownBy(() -> service.add(7L, 12L, request)).isSameAs(denied);
        verify(provider, never()).getLatestQuote("600519.SH");
    }

    @Test
    void rejectsUnknownAndDelistedStatuses() {
        when(provider.getLatestQuote("600519.SH"))
                .thenReturn(Optional.of(snapshot("UNKNOWN")))
                .thenReturn(Optional.of(snapshot("DELISTED")));

        assertThatThrownBy(() -> service.add(7L, 12L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_STATUS_UNAVAILABLE);
        assertThatThrownBy(() -> service.add(7L, 12L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.STOCK_NOT_AVAILABLE);
        verify(transactionService, never()).add(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void allowsSuspendedStockWithUsableQuote() {
        StockQuoteSnapshot snapshot = snapshot("SUSPENDED");
        AddWorkspaceStockResponseVO expected = new AddWorkspaceStockResponseVO(21L, 31L, false);
        when(provider.getLatestQuote("600519.SH")).thenReturn(Optional.of(snapshot));
        when(transactionService.add(7L, 12L, request, snapshot)).thenReturn(expected);

        assertThat(service.add(7L, 12L, request)).isEqualTo(expected);
    }

    private StockQuoteSnapshot snapshot(String listingStatus) {
        return new StockQuoteSnapshot(
                "600519.SH", "贵州茅台", "SSE", "CNY", listingStatus,
                new BigDecimal("1290.88"), LocalDateTime.of(2026, 9, 19, 0, 0),
                "LONGBRIDGE_OPENAPI", false);
    }
}
