package com.stock.system.agent.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** Chooses a local strategy; the caller still owns access checks before and after retrieval. */
@Component
public class ReportRetriever {
    private final ReportCatalog catalog;
    private final ReportVectorStore vectors;
    private final ReportRetrieval.Mode mode;
    private final boolean rerank;

    public ReportRetriever(ReportCatalog catalog, ReportVectorStore vectors,
            @Value("${stock-system.agent.reports.retrieval-mode:HYBRID}") ReportRetrieval.Mode mode,
            @Value("${stock-system.agent.reports.rerank:false}") boolean rerank) {
        this.catalog = catalog; this.vectors = vectors; this.mode = mode; this.rerank = rerank;
    }
    public boolean enabled() { return vectors.enabled(); }
    public List<ReportPassage> search(String query, String symbol, Integer year) {
        ReportRetrieval.validateScope(query, symbol, year);
        if (!enabled()) throw new IllegalStateException("Report retrieval is disabled");
        var snapshot = catalog.snapshot();
        var rows = snapshot.forSymbol(symbol).stream().filter(row -> year == null || row.reportYear() == year).toList();
        if (rows.isEmpty()) return List.of();
        var dense = mode == ReportRetrieval.Mode.KEYWORD ? List.<ReportVectorStore.Candidate>of()
                : vectors.candidates(query, symbol, year, mode == ReportRetrieval.Mode.VECTOR && !rerank ? 3 : ReportRetrieval.CANDIDATE_LIMIT);
        return ReportRetrieval.rank(query, rows, dense, mode, rerank).passages();
    }
}
