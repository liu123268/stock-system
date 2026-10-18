package com.stock.system.agent.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.vectorstore.SearchRequest;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Real local ONNX and Qdrant server, no Spring, MySQL, remote model or provider connection. */
@EnabledIfEnvironmentVariable(named = "REPORT_RAG_LOCAL_PROBE", matches = "true")
class LocalReportRetrievalProbeTest {
    @Test void runsTheFrozenChineseQuestionSetsThroughJavaAndTheOwnedQdrantServer() throws Exception {
        var catalog = new ReportCatalog();
        var json = JsonMapper.builder().build();
        Path parent = Path.of("target").toAbsolutePath().normalize();
        Path temporary = Files.createTempDirectory(parent, "report-rag-java-");
        var outcomes = new ArrayList<Map<String, Object>>();
        var store = new ReportVectorStore(catalog, true, ".local/report-rag/qdrant-1.19.2/qdrant.exe", ".local/rag-preflight/model", temporary.toString(), 16334);
        try {
            for (String file : List.of("questions.json", "holdout-questions.json")) {
                for (var item : json.readTree(Files.readAllBytes(Path.of("scripts/rag-preflight/" + file))).path("cases")) {
                    var expected = new ArrayList<String>();
                    item.path("expectedSections").forEach(section -> expected.add(section.asString()));
                    var documents = store.similaritySearch(SearchRequest.builder().query(item.path("query").asString()).topK(3)
                            .similarityThreshold(0.45).filterExpression("symbol == '" + item.path("symbol").asString() + "'").build());
                    var sections = documents.stream().map(document -> catalog.require(document.getId()).section()).toList();
                    outcomes.add(Map.of("id", item.path("id").asString(), "expected", expected, "actual", sections,
                            "scores", documents.stream().map(document -> document.getScore()).toList()));
                    if (expected.isEmpty()) assertThat(sections).as(item.path("id").asString()).isEmpty();
                    else assertThat(sections).as(item.path("id").asString()).containsAnyElementsOf(expected);
                }
            }
            assertThat(store.search("公司主要业务是什么", "000759.SZ", null)).isEmpty();
            assertThat(store.search("公司主要业务是什么", "600519.SH", 2024)).isEmpty();
            var before = store.search("公司主要业务是什么", "600519.SH", 2025);
            store.close();
            assertThat(store.search("公司主要业务是什么", "600519.SH", 2025)).isEqualTo(before);
            store.close();
            Process abandonedParent = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", System.getProperty("java.class.path"), Orphan.class.getName(), temporary.toString())
                    .redirectErrorStream(true).redirectOutput(temporary.resolve("orphan-parent.log").toFile()).start();
            try {
                assertThat(abandonedParent.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(abandonedParent.exitValue()).isZero();
                assertThat(store.search("公司主要业务是什么", "600519.SH", 2025)).isEqualTo(before);
            } finally { if (abandonedParent.isAlive()) { abandonedParent.destroyForcibly(); abandonedParent.waitFor(); } }
            Files.writeString(parent.resolve("report-rag-java-probe.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("embeddingVersion", LocalReportEmbedding.VERSION, "catalogSha256", catalog.fingerprint(), "cases", outcomes,
                            "checks", List.of("real-java-onnx-cls", "real-qdrant-rest", "wrong-stock-empty", "wrong-year-empty", "persistent-reopen-idempotent", "verified-orphan-recovery"))));
        } finally {
            store.close();
            try (var socket = new Socket()) {
                assertThatThrownBy(() -> socket.connect(new InetSocketAddress("127.0.0.1", 16334), 200)).isInstanceOf(ConnectException.class);
            }
            if (!temporary.getParent().equals(parent) || !temporary.getFileName().toString().startsWith("report-rag-java-"))
                throw new IllegalStateException("Unsafe report probe cleanup target");
            try (var files = Files.walk(temporary)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
            assertThat(temporary).doesNotExist();
        }
    }
    public static class Orphan {
        public static void main(String[] args) throws Exception {
            var store = new ReportVectorStore(new ReportCatalog(), true, ".local/report-rag/qdrant-1.19.2/qdrant.exe",
                    ".local/rag-preflight/model", args[0], 16334);
            store.search("公司主要业务是什么", "600519.SH", 2025);
            Runtime.getRuntime().halt(0); // Simulate the IDE terminating a parent without shutdown hooks.
        }
    }
}
