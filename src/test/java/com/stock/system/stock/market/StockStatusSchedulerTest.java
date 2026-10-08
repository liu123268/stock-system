package com.stock.system.stock.market;

import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.StockMapper;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StockStatusSchedulerTest {
    @Test
    void checksInBatchesAndMarksMissingRecordsWithoutOpeningAnotherGateway() throws Exception {
        StockMapper mapper = mock(StockMapper.class); LongbridgeQuoteGateway gateway = mock(LongbridgeQuoteGateway.class);
        StockStatusChecks checks = mock(StockStatusChecks.class); DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.getCatalog()).thenReturn("stock_system_test");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true); when(result.getInt(1)).thenReturn(1);
        Stock one = new Stock(); one.setId(10L); one.setCode("600519.SH");
        Stock two = new Stock(); two.setId(20L); two.setCode("920799.BJ");
        when(mapper.selectDueStatusChecks(any(), anyLong())).thenReturn(List.of(one, two), List.of());
        when(gateway.getQuotes(anyList())).thenReturn(Map.of());
        new StockStatusScheduler(mapper, gateway, checks, ds,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-08T00:00:00Z"), java.time.ZoneOffset.UTC)).updateDueStocks();
        verify(gateway).getQuotes(List.of("600519.SH", "920799.BJ"));
        verify(checks).observe(eq(one), isNull(), any()); verify(checks).observe(eq(two), isNull(), any());
        verify(connection).close(); verify(statement, times(2)).setString(1, "stock-status:stock_system_test");
        verify(mapper).selectDueStatusChecks(any(), eq(0L));
        verify(mapper).selectDueStatusChecks(any(), eq(20L));
    }

    @Test
    void doesNotConnectBeforeDailyCutoff() {
        StockMapper mapper = mock(StockMapper.class); LongbridgeQuoteGateway gateway = mock(LongbridgeQuoteGateway.class);
        StockStatusChecks checks = mock(StockStatusChecks.class); DataSource ds = mock(DataSource.class);
        new StockStatusScheduler(mapper, gateway, checks, ds,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-07T22:00:00Z"), java.time.ZoneOffset.UTC)).updateDueStocks();
        verifyNoInteractions(ds, mapper, gateway, checks);
    }

    @Test
    void anotherBackendHoldingTheDatabaseLockPreventsDuplicateQueries() throws Exception {
        StockMapper mapper = mock(StockMapper.class); LongbridgeQuoteGateway gateway = mock(LongbridgeQuoteGateway.class);
        StockStatusChecks checks = mock(StockStatusChecks.class); DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.getCatalog()).thenReturn("stock_system_test");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true); when(result.getInt(1)).thenReturn(0);
        new StockStatusScheduler(mapper, gateway, checks, ds,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-08T00:00:00Z"), java.time.ZoneOffset.UTC)).updateDueStocks();
        verifyNoInteractions(mapper, gateway, checks);
        verify(connection).close();
        verify(connection, never()).prepareStatement("select release_lock(?)");
    }

    @Test
    void anUpstreamOutageIsRecordedWithoutPerStockRetriesAndReleasesTheLock() throws Exception {
        StockMapper mapper = mock(StockMapper.class); LongbridgeQuoteGateway gateway = mock(LongbridgeQuoteGateway.class);
        StockStatusChecks checks = mock(StockStatusChecks.class); DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.getCatalog()).thenReturn("stock_system_test");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true); when(result.getInt(1)).thenReturn(1);
        Stock stock = new Stock(); stock.setId(8L); stock.setCode("600519.SH");
        when(mapper.selectDueStatusChecks(any(), anyLong())).thenReturn(List.of(stock), List.of());
        when(gateway.getQuotes(anyList())).thenThrow(new LongbridgeUpstreamException("unavailable"));
        new StockStatusScheduler(mapper, gateway, checks, ds,
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-08T00:00:00Z"), java.time.ZoneOffset.UTC)).updateDueStocks();
        verify(gateway, times(1)).getQuotes(List.of("600519.SH"));
        verify(checks).observe(eq(stock), isNull(), any());
        verify(connection).prepareStatement("select release_lock(?)");
        verify(connection).close();
    }
}
