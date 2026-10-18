package com.stock.system.agent.service;

import com.stock.system.agent.mapper.ResearchAgentRecordMapper.Target;
import com.stock.system.agent.report.ReportCatalog;
import com.stock.system.agent.report.ReportPassage;
import com.stock.system.agent.report.ReportVectorStore;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.service.StockResearchSourceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ResearchReportService {
    private final StockResearchSourceService access;
    private final WorkspaceStockMapper stocks;
    private final ReportCatalog catalog;
    private final ReportVectorStore vectors;

    public ResearchReportService(StockResearchSourceService access, WorkspaceStockMapper stocks,
                                 ReportCatalog catalog, ReportVectorStore vectors) {
        this.access = access; this.stocks = stocks; this.catalog = catalog; this.vectors = vectors;
    }
    private String symbol(Target target) {
        access.verifyReadAccess(target.user(), target.workspace(), target.stock(), target.cycle());
        var stock = stocks.selectStockIdentity(target.workspace(), target.stock());
        if (stock == null) throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
        return stock.getCode();
    }
    public Library library(Target target) {
        String symbol = symbol(target);
        var rows = catalog.forSymbol(symbol);
        var reports = rows.stream().map(ReportPassage::reportVersion).distinct().map(version -> {
            var parts = rows.stream().filter(row -> row.reportVersion().equals(version)).toList();
            var first = parts.get(0);
            return new Report(first.reportTitle(), first.reportYear(), first.sourceUrl(), first.reportVersion(),
                    parts.stream().map(ReportPassage::sectionTitle).distinct().toList(),
                    parts.stream().map(ReportPassage::pdfPage).distinct().sorted().toList());
        }).toList();
        return new Library(vectors.enabled(), reports, "仅检索已核对的文字章节，不覆盖整份年报或全部最新披露。观点日期范围不限制报告年份。", catalog.statuses(symbol));
    }
    @Transactional(propagation = Propagation.NEVER)
    public Search search(Target target, String query, Integer year) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 240
                || year != null && (year < 2000 || year > 2100)) throw new IllegalArgumentException();
        String symbol = symbol(target);
        var rows = catalog.forSymbol(symbol).stream().filter(row -> year == null || row.reportYear() == year).toList();
        if (rows.isEmpty()) return new Search("NO_REPORT", List.of(), "当前股票或指定年份没有已入库的报告章节，未查询其他股票或年份。");
        if (!vectors.enabled()) return new Search("UNAVAILABLE", List.of(), "报告段落检索尚未启用。");
        List<ReportPassage> hits;
        try { hits = vectors.search(query, symbol, year); }
        catch (RuntimeException failure) { return new Search("UNAVAILABLE", List.of(), "报告检索暂时不可用，未返回未经核对的原文。"); }
        if (!symbol.equals(symbol(target))) throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
        hits.forEach(row -> verify(target, row));
        if (hits.stream().anyMatch(row -> !catalog.matchesCurrent(row))) throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
        return new Search(hits.isEmpty() ? "NO_MATCH" : "AVAILABLE", hits,
                hits.isEmpty() ? "已入库章节未找到足够相关的段落；不能据此认定整份报告没有该信息。"
                        : "这是报告原文候选，须核对是否支持本次结论；报告中的判断与展望不代表当前已实现事实。");
    }
    public void verify(Target target, ReportPassage snapshot) {
        if (snapshot == null || !symbol(target).equals(snapshot.symbol()))
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
        boolean matches;
        try {
            matches = catalog.matches(snapshot);
        } catch (RuntimeException failure) {
            // A corrupt/unreadable publication cannot authorize a saved original from message JSON.
            throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
        }
        if (!matches) throw new BusinessException(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED);
    }
    public record Search(String status, List<ReportPassage> passages, String note) {}
    public record Library(boolean retrievalEnabled, List<Report> reports, String note, List<com.stock.system.agent.report.ReportLibraryStore.Status> states) {}
    public record Report(String title, int year, String url, String version, List<String> sections, List<Integer> pdfPages) {}
}
