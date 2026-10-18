package com.stock.system.agent;

import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.report.*;
import com.stock.system.agent.service.ResearchReportService;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.service.StockResearchSourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResearchReportServiceTest {
    final Target target = new Target(1L, 2L, 3L, 4L);
    final StockResearchSourceService access = mock(StockResearchSourceService.class);
    final WorkspaceStockMapper stocks = mock(WorkspaceStockMapper.class);
    final ReportRetriever vectors = mock(ReportRetriever.class);
    final ReportCatalog catalog = new ReportCatalog();
    final ResearchReportService service = new ResearchReportService(access, stocks, catalog, vectors);
    ResearchReportServiceTest() throws Exception {}
    @BeforeEach void fixture() {
        var stock = new Stock(); stock.setCode("600519.SH");
        when(stocks.selectStockIdentity(2L, 3L)).thenReturn(stock);
        when(vectors.enabled()).thenReturn(true);
    }
    @Test void bindsStockAndYearWithoutUsingTheOpinionDateFilter() {
        var rows = catalog.forSymbol("600519.SH").subList(0, 1);
        when(vectors.search("公司主要业务", "600519.SH", 2025)).thenReturn(rows);
        assertThat(service.search(target, "公司主要业务", 2025).passages()).isEqualTo(rows);
        verify(access, atLeastOnce()).verifyReadAccess(1L, 2L, 3L, 4L);
    }
    @Test void noReportYearDoesNotFallBackToAnotherYear() {
        assertThat(service.search(target, "公司主要业务", 2024).status()).isEqualTo("NO_REPORT");
        verify(vectors, never()).search(anyString(), anyString(), any());
    }
    @Test void lostAccessDoesNotReachTheVectorStore() {
        doThrow(new BusinessException(ErrorCode.WORKSPACE_ACCESS_DENIED)).when(access).verifyReadAccess(1L, 2L, 3L, 4L);
        assertThatThrownBy(() -> service.search(target, "公司主要业务", 2025)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(vectors);
    }
    @Test void wrongStockPayloadCannotBecomeEvidence() {
        when(vectors.search(anyString(), anyString(), any())).thenReturn(catalog.forSymbol("00700.HK").subList(0, 1));
        assertThatThrownBy(() -> service.search(target, "主要业务", 2025)).isInstanceOf(BusinessException.class);
    }
    @Test void unavailableStoreDoesNotPretendNoAnswer() {
        when(vectors.search(anyString(), anyString(), any())).thenThrow(new IllegalStateException("private details"));
        assertThat(service.search(target, "主要业务", null).status()).isEqualTo("UNAVAILABLE");
        assertThat(service.library(target).reports()).hasSize(1);
    }
    @Test void invalidQueryDoesNotStartRetrieval() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.search(target, " ", null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.search(target, "问".repeat(241), null));
        verifyNoInteractions(access, stocks, vectors);
    }
    @Test void unreadablePublicationInvalidatesSavedOriginalRatherThanRestoringMessageText() {
        var unavailable = mock(ReportCatalog.class);
        var row = catalog.forSymbol("600519.SH").get(0);
        when(unavailable.matches(row)).thenThrow(new IllegalStateException("unreadable local publication"));
        var guarded = new ResearchReportService(access, stocks, unavailable, vectors);
        assertThatThrownBy(() -> guarded.verify(target, row)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED));
    }
}
