package com.stock.system.stock.service;

import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.entity.ResearchCycle;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.entity.WorkspaceStock;
import com.stock.system.stock.mapper.ResearchCycleMapper;
import com.stock.system.stock.mapper.StockMapper;
import com.stock.system.stock.mapper.StockOpinionMapper;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.market.StockQuoteSnapshot;
import com.stock.system.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockResearchTransactionServiceTest {

    @Test
    void realResearchUpdatesOnlyVerifiedStatusAndKeepsDirectoryIdentity() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        StockMapper stockMapper = mock(StockMapper.class);
        WorkspaceStockMapper workspaceStockMapper = mock(WorkspaceStockMapper.class);
        ResearchCycleMapper researchCycleMapper = mock(ResearchCycleMapper.class);
        StockOpinionMapper opinionMapper = mock(StockOpinionMapper.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        StockResearchTransactionService service = new StockResearchTransactionService(
                workspaceService, stockMapper, workspaceStockMapper, researchCycleMapper, opinionMapper,
                jdbcTemplate);

        Stock directoryStock = new Stock();
        directoryStock.setId(1L);
        directoryStock.setCode("600519.SH");
        directoryStock.setName("目录中的贵州茅台");
        directoryStock.setExchange("SSE");
        directoryStock.setCurrency("CNY");
        directoryStock.setListingStatus("UNKNOWN");
        directoryStock.setSource("SSE_DIRECTORY");
        when(stockMapper.selectDirectoryByCodeForUpdate("600519.SH")).thenReturn(directoryStock);

        WorkspaceStock workspaceStock = new WorkspaceStock();
        workspaceStock.setId(21L);
        when(workspaceStockMapper.selectOne(any())).thenReturn(workspaceStock);
        when(researchCycleMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<ResearchCycle>getArgument(0).setId(31L);
            return 1;
        }).when(researchCycleMapper).insert(any(ResearchCycle.class));

        StockQuoteSnapshot snapshot = new StockQuoteSnapshot(
                "600519.SH", "行情中的名称", "SSE", "CNY", "SUSPENDED",
                new BigDecimal("1290.88"), LocalDateTime.of(2026, 9, 19, 0, 0),
                "LONGBRIDGE_OPENAPI", false);
        AddWorkspaceStockRequestDTO request = new AddWorkspaceStockRequestDTO(
                "600519.SH", "BULLISH", "长期品牌优势", null, "MEDIUM", null, null);

        var result = service.add(7L, 12L, request, snapshot);

        assertThat(result.researchCycleId()).isEqualTo(31L);
        verify(stockMapper).updateListingStatus(1L, "SUSPENDED");
        verify(stockMapper, never()).insertIfAbsent(any());
        assertThat(directoryStock.getName()).isEqualTo("目录中的贵州茅台");
        assertThat(directoryStock.getSource()).isEqualTo("SSE_DIRECTORY");
    }
}
