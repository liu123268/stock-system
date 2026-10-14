package com.stock.system.stock.service;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.financial.FinancialFacts;
import com.stock.system.stock.financial.FinancialMetric;
import com.stock.system.stock.financial.FinancialPeriod;
import com.stock.system.stock.financial.FinancialSourceGateway;
import com.stock.system.stock.financial.LongbridgeFinancialParser;
import com.stock.system.stock.financial.OfficialFinancialReportCatalog;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.workspace.service.WorkspaceService;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Financial reads do not depend on quote availability, and never keep a database transaction open upstream. */
@Service
public class StockFinancialService {
    private final WorkspaceService workspaces;
    private final WorkspaceStockMapper stocks;
    private final ObjectProvider<@NonNull FinancialSourceGateway> gateways;
    private final LongbridgeFinancialParser parser;
    private final OfficialFinancialReportCatalog officialReports;

    public StockFinancialService(WorkspaceService workspaces, WorkspaceStockMapper stocks,
                                 ObjectProvider<@NonNull FinancialSourceGateway> gateways, LongbridgeFinancialParser parser,
                                 OfficialFinancialReportCatalog officialReports) {
        this.workspaces = workspaces;
        this.stocks = stocks;
        this.gateways = gateways;
        this.parser = parser;
        this.officialReports = officialReports;
    }

    @Transactional(propagation = Propagation.NEVER)
    public FinancialFacts read(Long userId, Long workspaceId, Long workspaceStockId, FinancialPeriod series,
                               String requestedPeriod, List<FinancialMetric> requestedMetrics) {
        if (workspaceId == null || workspaceId <= 0 || workspaceStockId == null || workspaceStockId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String period;
        List<FinancialMetric> metrics;
        try {
            period = LongbridgeFinancialParser.requirePeriod(series, requestedPeriod);
            metrics = LongbridgeFinancialParser.requireMetrics(requestedMetrics);
        } catch (IllegalArgumentException ignored) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
        Stock stock = requireStock(userId, workspaceId, workspaceStockId);
        try { FinancialSourceGateway.validateSymbol(stock.getCode()); }
        catch (IllegalArgumentException ignored) { return unavailable(stock, series, metrics, "UNSUPPORTED_MARKET"); }
        FinancialFacts verified = officialReports.read(stock.getCode(), series, period, metrics);
        if (verified != null) {
            Stock current = requireStock(userId, workspaceId, workspaceStockId);
            if (!stock.getCode().equals(current.getCode())) throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
            return verified;
        }
        FinancialSourceGateway gateway = gateways.getIfAvailable();
        if (gateway == null) return unavailable(stock, series, metrics, "SOURCE_NOT_CONNECTED");
        FinancialFacts result;
        try {
            result = parser.parse(stock.getCode(), series, period, metrics, gateway.readFinancialReport(stock.getCode(), series));
        } catch (FinancialSourceGateway.SourceFailure failure) {
            result = unavailable(stock, series, metrics, failure.reason().name());
        } catch (RuntimeException ignored) {
            // No provider body, exception message, credentials or guessed zero values leave the service.
            result = unavailable(stock, series, metrics, "SOURCE_FAILURE");
        }
        Stock current = requireStock(userId, workspaceId, workspaceStockId);
        if (!stock.getCode().equals(current.getCode())) throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        return result;
    }

    public void verifySnapshot(Long userId, Long workspaceId, Long workspaceStockId, FinancialFacts snapshot) {
        Stock stock = requireStock(userId, workspaceId, workspaceStockId);
        if (!stock.getCode().equals(snapshot.symbol()) || !officialReports.matches(snapshot))
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
    }

    private Stock requireStock(Long userId, Long workspaceId, Long workspaceStockId) {
        workspaces.detail(userId, workspaceId);
        Stock stock = stocks.selectStockIdentity(workspaceId, workspaceStockId);
        if (stock == null) throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        return stock;
    }

    private static FinancialFacts unavailable(Stock stock, FinancialPeriod series, List<FinancialMetric> metrics, String reason) {
        return new FinancialFacts("UNAVAILABLE", stock.getCode(), series, null, List.of(), List.of(), metrics,
                null, null, null, reason, "财务资料暂不可用；缺失不代表金额为零。");
    }
}
