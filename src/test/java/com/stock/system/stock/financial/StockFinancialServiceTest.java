package com.stock.system.stock.financial;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.service.StockFinancialService;
import com.stock.system.workspace.service.WorkspaceService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockFinancialServiceTest {
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final WorkspaceStockMapper stocks = mock(WorkspaceStockMapper.class);
    private final FinancialSourceGateway gateway = mock(FinancialSourceGateway.class);
    private final OfficialFinancialReportCatalog officialReports = mock(OfficialFinancialReportCatalog.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<@NonNull FinancialSourceGateway> gateways = mock(ObjectProvider.class);
    private final StockFinancialService service = new StockFinancialService(workspaces, stocks, gateways,
            new LongbridgeFinancialParser(new ObjectMapper()), officialReports);
    private final Stock stock = new Stock();

    @BeforeEach void setUp() {
        stock.setCode("600519.SH");
        when(stocks.selectStockIdentity(2L, 3L)).thenReturn(stock);
        when(gateways.getIfAvailable()).thenReturn(gateway);
        when(gateway.readFinancialReport("600519.SH", FinancialPeriod.ANNUAL))
                .thenReturn(new FinancialSourceGateway.SourceReport("{}", Instant.parse("2026-10-14T04:50:00Z"), "LONGBRIDGE_FINANCIAL_REPORT"));
    }
    private FinancialFacts read() { return service.read(1L, 2L, 3L, FinancialPeriod.ANNUAL, null, List.of(FinancialMetric.REVENUE)); }

    @Test void verifiedAnnualReportIsIndependentOfQuoteAndUnverifiedProviderAvailability() {
        var verified = new OfficialFinancialReportCatalog().read("600519.SH", FinancialPeriod.ANNUAL,
                null, List.of(FinancialMetric.REVENUE));
        when(officialReports.read("600519.SH", FinancialPeriod.ANNUAL, null, List.of(FinancialMetric.REVENUE))).thenReturn(verified);
        assertThat(read()).isEqualTo(verified);
        verifyNoInteractions(gateway);
        verify(workspaces, times(2)).detail(1L, 2L);
    }

    @Test void removedReportVersionOrChangedStockInvalidatesStoredFinancialEvidence() {
        var verified = new OfficialFinancialReportCatalog().read("600519.SH", FinancialPeriod.ANNUAL,
                null, List.of(FinancialMetric.REVENUE));
        when(officialReports.matches(verified)).thenReturn(false);
        assertThatThrownBy(() -> service.verifySnapshot(1L, 2L, 3L, verified))
                .isInstanceOf(BusinessException.class).extracting(failure -> ((BusinessException) failure).getErrorCode())
                .isEqualTo(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
    }

    @Test void resolvesTrustedStockIdentityAndRechecksAccessAfterReading() {
        assertThat(read().status()).isEqualTo("NO_DATA");
        var ordered = inOrder(workspaces, stocks, gateway);
        ordered.verify(workspaces).detail(1L, 2L);
        ordered.verify(stocks).selectStockIdentity(2L, 3L);
        ordered.verify(gateway).readFinancialReport("600519.SH", FinancialPeriod.ANNUAL);
        ordered.verify(workspaces).detail(1L, 2L);
        ordered.verify(stocks).selectStockIdentity(2L, 3L);
    }
    @Test void deniedAccessNeverCallsProvider() {
        when(workspaces.detail(1L, 2L)).thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class);
        verifyNoInteractions(gateway);
    }
    @Test void lossOfAccessDuringReadingPreventsReturningProviderData() {
        when(workspaces.detail(1L, 2L)).thenReturn(null).thenThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED));
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_ACCESS_DENIED);
        verify(gateway).readFinancialReport("600519.SH", FinancialPeriod.ANNUAL);
    }
    @Test void deletedStockDuringReadDoesNotReturnData() {
        when(stocks.selectStockIdentity(2L, 3L)).thenReturn(stock, (Stock) null);
        assertThatThrownBy(this::read).isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
    }
    @Test void unknownMarketsAndUnconnectedProviderAreDifferentGaps() {
        stock.setCode("AAPL.US");
        assertThat(read().unavailableReason()).isEqualTo("UNSUPPORTED_MARKET");
        stock.setCode("600519.SH");
        when(gateways.getIfAvailable()).thenReturn(null);
        var result = read();
        assertThat(result.unavailableReason()).isEqualTo("SOURCE_NOT_CONNECTED");
        assertThat(result.facts()).isEmpty();
        verifyNoInteractions(gateway);
    }
    @ParameterizedTest @EnumSource(FinancialSourceGateway.FailureReason.class)
    void sourceFailuresRetainSafeReasonAndNeverInventZero(FinancialSourceGateway.FailureReason reason) {
        when(gateway.readFinancialReport(anyString(), any())).thenThrow(new FinancialSourceGateway.SourceFailure(reason));
        var result = read();
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.unavailableReason()).isEqualTo(reason.name());
        assertThat(result.facts()).isEmpty();
        assertThat(result.missingMetrics()).containsExactly(FinancialMetric.REVENUE);
    }
    @Test void doesNotExposeRawProviderFailureDetails() {
        when(gateway.readFinancialReport(anyString(), any())).thenThrow(new IllegalStateException("sensitive-provider-body"));
        var result = read();
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("sensitive-provider-body");
        assertThat(result.unavailableReason()).isEqualTo("SOURCE_FAILURE");
    }
    @Test void invalidRequestStopsBeforeAnyDatabaseOrSourceAccess() {
        assertThatThrownBy(() -> service.read(1L, 2L, 3L, FinancialPeriod.ANNUAL, "Q2 2026", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.read(1L, 2L, 3L, FinancialPeriod.ANNUAL, null,
                List.of(FinancialMetric.REVENUE, FinancialMetric.REVENUE))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(workspaces, stocks, gateway);
    }
}
