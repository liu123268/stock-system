package com.stock.system.agent.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Frozen retrieval comparison using real local ONNX/Qdrant; answer quality is a separate check. */
@EnabledIfEnvironmentVariable(named = "REPORT_RAG_LOCAL_PROBE", matches = "true")
class ReportRetrievalEvaluationTest {
    record Strategy(String name, ReportRetrieval.Mode mode, boolean rerank) {}
    private static final List<Strategy> STRATEGIES = List.of(
            new Strategy("vector", ReportRetrieval.Mode.VECTOR, false),
            new Strategy("keyword", ReportRetrieval.Mode.KEYWORD, false),
            new Strategy("hybrid", ReportRetrieval.Mode.HYBRID, false),
            new Strategy("hybrid-reranked", ReportRetrieval.Mode.HYBRID, true));

    @Test void comparesFrozenQuestionsWithoutRetuningTheHoldoutOrCallingAPaidModel() throws Exception {
        var catalog = new ReportCatalog();
        var json = JsonMapper.builder().build();
        Path parent = Path.of("target").toAbsolutePath().normalize();
        Path temporary = Files.createTempDirectory(parent, "report-ranking-");
        ReportVectorStore store = null;
        var cases = new ArrayList<Map<String, Object>>();
        var hashes = new LinkedHashMap<String, String>();
        var summaries = new LinkedHashMap<String, Object>();
        try {
            store = new ReportVectorStore(catalog, true, ".local/report-rag/qdrant-1.19.2/qdrant.exe",
                    ".local/rag-preflight/model", temporary.toString(), 16334);
            long setup = System.nanoTime(); store.prepare(catalog);
            double setupMs = millis(setup);
            for (String file : List.of("questions.json", "holdout-questions.json")) {
                byte[] bytes = Files.readAllBytes(Path.of("scripts/rag-preflight", file));
                hashes.put(file, ReportCatalog.sha256(bytes));
                String split = json.readTree(bytes).path("split").asString();
                var totals = new LinkedHashMap<String, double[]>();
                for (var strategy : STRATEGIES) totals.put(strategy.name(), new double[7]);
                for (var item : json.readTree(bytes).path("cases")) {
                    var expected = new ArrayList<String>(); item.path("expectedSections").forEach(section -> expected.add(section.asString()));
                    String query = item.path("query").asString(), symbol = item.path("symbol").asString();
                    long encoded = System.nanoTime();
                    var dense = store.candidates(query, symbol, null, ReportRetrieval.CANDIDATE_LIMIT);
                    double denseMs = millis(encoded);
                    var results = new LinkedHashMap<String, Object>();
                    for (var strategy : STRATEGIES) {
                        var candidates = strategy.mode() == ReportRetrieval.Mode.KEYWORD ? List.<ReportVectorStore.Candidate>of()
                                : strategy.mode() == ReportRetrieval.Mode.VECTOR ? dense.stream().limit(3).toList() : dense;
                        long ranked = System.nanoTime();
                        var result = ReportRetrieval.rank(query, catalog.forSymbol(symbol), candidates, strategy.mode(), strategy.rerank());
                        double rankMs = millis(ranked);
                        int rank = 0;
                        for (int i = 0; i < result.hits().size(); i++) if (expected.contains(result.hits().get(i).passage().section())) { rank = i + 1; break; }
                        var counts = totals.get(strategy.name());
                        if (expected.isEmpty()) { counts[3]++; if (result.hits().isEmpty()) counts[4]++; }
                        else { counts[0]++; if (rank > 0) { counts[1]++; counts[2] += 1.0 / rank; } if (rank == 1) counts[5]++; }
                        counts[6] += rankMs + (strategy.mode() == ReportRetrieval.Mode.KEYWORD ? 0 : denseMs);
                        results.put(strategy.name(), Map.of("firstRelevantRank", rank, "hits", result.hits(), "rankingMs", rankMs,
                                "vectorCandidates", result.vectorCandidates(), "keywordCandidates", result.keywordCandidates()));
                    }
                    cases.add(Map.of("id", item.path("id").asString(), "split", split, "query", query, "symbol", symbol,
                            "expectedSections", expected, "denseMs", denseMs, "strategies", results));
                }
                var set = new LinkedHashMap<String, Object>();
                totals.forEach((name, counts) -> set.put(name, Map.of("answerable", (int) counts[0], "hitAt3", (int) counts[1],
                        "mrrAt3", counts[0] == 0 ? 0 : counts[2] / counts[0], "noAnswer", (int) counts[3],
                        "noAnswerRejected", (int) counts[4], "hitAt1", (int) counts[5], "meanMs", counts[6] / (counts[0] + counts[3]))));
                summaries.put(split, set);
                assertThat(totals.get("vector")[1]).isEqualTo(10);
                assertThat(totals.get("vector")[4]).isEqualTo(2);
                assertThat(totals.get("hybrid")[1]).isEqualTo(10);
                assertThat(totals.get("hybrid")[4]).isEqualTo(2);
                assertThat(totals.get("hybrid")[5]).isGreaterThanOrEqualTo(totals.get("vector")[5]);
            }
            var report = new LinkedHashMap<String, Object>();
            report.put("retrievalVersion", ReportRetrieval.VERSION); report.put("embeddingVersion", LocalReportEmbedding.VERSION);
            report.put("catalogSha256", catalog.fingerprint()); report.put("questionSetSha256", hashes); report.put("setupMs", setupMs);
            report.put("parameters", Map.of("candidateLimit", 12, "resultLimit", 3, "minVectorScore", 0.45, "rrfK", 60,
                    "minKeywordCoverage", 0.20, "reranker", "local-idf-query-coverage"));
            report.put("quality", summaries); report.put("cases", cases); report.put("paidRequests", 0);
            report.put("selected", Map.of("mode", "HYBRID", "rerank", false,
                    "basis", "calibration-hitAt1-noAnswer-hitAt3; holdout-not-used-to-retune"));
            report.put("boundaries", List.of("retrieval-only-not-answer-support", "select-on-calibration-only", "tiny-reviewed-corpus",
                    "latency-single-pass-shared-encoding-not-a-benchmark"));
            Files.writeString(parent.resolve("report-retrieval-comparison.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } finally {
            try {
                if (store != null) store.close();
                try (var socket = new Socket()) {
                    assertThatThrownBy(() -> socket.connect(new InetSocketAddress("127.0.0.1", 16334), 200)).isInstanceOf(ConnectException.class);
                }
            } finally {
                if (!temporary.getParent().equals(parent) || !temporary.getFileName().toString().startsWith("report-ranking-"))
                    throw new IllegalStateException("Unsafe ranking probe cleanup target");
                try (var files = Files.walk(temporary)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
                assertThat(temporary).doesNotExist();
            }
        }
    }
    private static double millis(long started) { return (System.nanoTime() - started) / 1_000_000.0; }
}
