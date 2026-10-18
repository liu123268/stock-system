package com.stock.system.agent.report;

import ai.onnxruntime.OrtException;
import jakarta.annotation.PreDestroy;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.time.Duration;
import java.util.*;

/** Local Qdrant REST adapter. It accepts only reviewed publications and bounded stock filters. */
@Component
public class ReportVectorStore implements VectorStore, AutoCloseable {
    private final ReportCatalog catalog;
    private final boolean enabled;
    private final Path binary, models, directory;
    private final int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String collection;
    private ReportCatalog indexedCatalog;
    private Process process;
    private LocalReportEmbedding embedding;
    private boolean ready;
    private FileChannel lockChannel;
    private FileLock ownership;

    public ReportVectorStore(ReportCatalog catalog,
            @Value("${stock-system.agent.reports.enabled:false}") boolean enabled,
            @Value("${stock-system.agent.reports.qdrant-binary:.local/report-rag/qdrant-1.19.2/qdrant.exe}") String binary,
            @Value("${stock-system.agent.reports.model-directory:.local/rag-preflight/model}") String models,
            @Value("${stock-system.agent.reports.storage-directory:.local/report-rag/runtime}") String directory,
            @Value("${stock-system.agent.reports.port:16333}") int port) {
        this.catalog = catalog; this.enabled = enabled; this.binary = Path.of(binary).toAbsolutePath().normalize();
        this.models = Path.of(models).toAbsolutePath().normalize(); this.directory = Path.of(directory).toAbsolutePath().normalize();
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid local report port");
        this.port = port;
        // Short paths also matter on Windows: Qdrant appends segment UUIDs and file names.
        selectCatalog(catalog.installed());
    }
    public boolean enabled() { return enabled; }

    public synchronized List<ReportPassage> search(String query, String symbol, Integer year) {
        return candidates(query, symbol, year, 3).stream().map(Candidate::passage).toList();
    }

    public synchronized List<Candidate> candidates(String query, String symbol, Integer year, int limit) {
        // Internal filter values come only from the authorized business target and validated year.
        ReportRetrieval.validateScope(query, symbol, year);
        String filter = "symbol == '" + symbol + "'" + (year == null ? "" : " && reportYear == " + year);
        return similaritySearch(SearchRequest.builder().query(query).topK(limit).similarityThreshold(0.45)
                .filterExpression(filter).build()).stream()
                .map(document -> new Candidate(indexedCatalog.require(document.getId()), document.getScore())).toList();
    }
    public record Candidate(ReportPassage passage, double score) {}

    public synchronized void prepare(ReportCatalog candidate) { selectCatalog(candidate); ensureReady(); }
    private void selectCatalog(ReportCatalog candidate) {
        String selected = "reports_" + ReportCatalog.sha256((candidate.fingerprint() + LocalReportEmbedding.VERSION)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 20);
        if (!selected.equals(collection)) { collection = selected; indexedCatalog = candidate; ready = false; }
    }

    @Override public synchronized List<Document> similaritySearch(SearchRequest request) {
        if (!enabled || request.getTopK() < 1 || request.getTopK() > ReportRetrieval.CANDIDATE_LIMIT
                || request.getQuery().isBlank() || request.getQuery().codePointCount(0, request.getQuery().length()) > 240)
            throw new IllegalArgumentException("Unsupported report search");
        var must = new ArrayList<Object>();
        stockFilters(request.getFilterExpression(), must);
        if (must.stream().noneMatch(item -> item instanceof Map<?, ?> map && "symbol".equals(map.get("key"))))
            throw new IllegalArgumentException("A bound stock is required");
        must.add(match("embeddingVersion", LocalReportEmbedding.VERSION));
        must.add(match("status", "AVAILABLE"));
        selectCatalog(catalog.snapshot());
        ensureReady();
        float[] vector = embedding.embed(LocalReportEmbedding.QUERY_PREFIX + request.getQuery());
        var result = request("POST", "/collections/" + collection + "/points/query", Map.of("query", vector,
                "filter", Map.of("must", must), "limit", request.getTopK(), "score_threshold", Math.max(0.45, request.getSimilarityThreshold()), "with_payload", true));
        var output = new ArrayList<Document>();
        for (JsonNode hit : result.path("result").path("points")) {
            var passage = indexedCatalog.require(hit.path("id").asString());
            var payload = hit.path("payload");
            double score = hit.path("score").asDouble();
            if (!Double.isFinite(score) || score < 0.45 || !passage.symbol().equals(payload.path("symbol").asString())
                    || !passage.reportVersion().equals(payload.path("reportVersion").asString())
                    || !passage.textSha256().equals(payload.path("textSha256").asString())
                    || !LocalReportEmbedding.VERSION.equals(payload.path("embeddingVersion").asString())
                    || !"AVAILABLE".equals(payload.path("status").asString())
                    || !matchesFilters(request.getFilterExpression(), passage))
                throw new IllegalStateException("Report vector payload no longer matches the source catalog");
            output.add(Document.builder().id(passage.id()).text(passage.text()).score(score).build());
        }
        if (output.size() > request.getTopK()) throw new IllegalStateException("Report candidate limit exceeded");
        return List.copyOf(output);
    }

    private static Map<String, Object> match(String key, Object value) { return Map.of("key", key, "match", Map.of("value", value)); }
    private static void stockFilters(Filter.Expression expression, List<Object> output) {
        if (expression == null) throw new IllegalArgumentException("Report filters are required");
        if (expression.type() == Filter.ExpressionType.AND && expression.left() instanceof Filter.Expression left
                && expression.right() instanceof Filter.Expression right) { stockFilters(left, output); stockFilters(right, output); return; }
        if (expression.type() != Filter.ExpressionType.EQ || !(expression.left() instanceof Filter.Key key)
                || !(expression.right() instanceof Filter.Value value) || !Set.of("symbol", "reportYear").contains(key.key()))
            throw new IllegalArgumentException("Unsupported report filter");
        output.add(match(key.key(), value.value()));
    }
    private static boolean matchesFilters(Filter.Expression expression, ReportPassage row) {
        if (expression.type() == Filter.ExpressionType.AND) return matchesFilters((Filter.Expression) expression.left(), row)
                && matchesFilters((Filter.Expression) expression.right(), row);
        String key = ((Filter.Key) expression.left()).key(); Object value = ((Filter.Value) expression.right()).value();
        return key.equals("symbol") ? row.symbol().equals(value) : value instanceof Number number && row.reportYear() == number.intValue();
    }

    private void ensureReady() {
        if (ready && process != null && process.isAlive()) return;
        try {
            close();
            Files.createDirectories(directory);
            lockChannel = FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            ownership = lockChannel.tryLock();
            if (ownership == null) throw new IllegalStateException("Report data directory belongs to another application");
            recoverOrphan();
            // Never reuse or stop a listener that this application did not create.
            try (var connection = new Socket()) {
                connection.connect(new InetSocketAddress("127.0.0.1", port), 200);
                throw new IllegalStateException("Local report port belongs to another listener");
            } catch (ConnectException unused) { /* no active listener; TIME_WAIT is not a running server */ }
            try (var socket = new java.net.ServerSocket()) { socket.setReuseAddress(true); socket.bind(new InetSocketAddress("127.0.0.1", port)); }
            if (!ReportCatalog.sha256(Files.readAllBytes(binary)).equals("b25bcc612583333289b00754907639ee97cd28b8741d50f1b4ce0e2bd1ad12eb"))
                throw new IllegalStateException("Qdrant executable identity changed");
            Files.createDirectories(directory);
            var config = directory.resolve("qdrant.yaml");
            String yaml = "telemetry_disabled: true\nlog_level: WARN\nservice:\n  host: 127.0.0.1\n  http_port: " + port
                    + "\n  grpc_port: null\nstorage:\n  storage_path: " + json.writeValueAsString(directory.resolve("storage").toString())
                    + "\n  snapshots_path: " + json.writeValueAsString(directory.resolve("snapshots").toString()) + "\n";
            Files.writeString(config, yaml);
            process = new ProcessBuilder(binary.toString(), "--config-path", config.toString(), "--disable-telemetry")
                    .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("qdrant.log").toFile())).start();
            Files.writeString(directory.resolve("owned-process.json"), json.writeValueAsString(Map.of("pid", process.pid(),
                    "startedAt", process.info().startInstant().orElseThrow().toString(), "binary", binary.toString(), "port", port,
                    "parentPid", ProcessHandle.current().pid(), "parentStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                    "dataDirectory", directory.toString())));
            boolean started = false;
            for (int i = 0; i < 40 && process.isAlive(); i++) {
                try { request("GET", "/", null); started = true; break; }
                catch (RuntimeException wait) { Thread.sleep(100); }
            }
            if (!started) throw new IllegalStateException("Owned Qdrant did not become ready");
            embedding = new LocalReportEmbedding(models);
            var collections = request("GET", "/collections", null).path("result").path("collections");
            boolean exists = false;
            for (var entry : collections) if (collection.equals(entry.path("name").asString())) exists = true;
            if (!exists) request("PUT", "/collections/" + collection, Map.of("vectors", Map.of("size", 512, "distance", "Cosine")));
            var configVector = request("GET", "/collections/" + collection, null).path("result").path("config").path("params").path("vectors");
            if (configVector.path("size").asInt() != 512 || !"Cosine".equals(configVector.path("distance").asString()))
                throw new IllegalStateException("Report vector collection configuration changed");
            add(indexedCatalog.all().stream().map(row -> Document.builder().id(row.id()).text(row.text()).build()).toList());
            int count = request("POST", "/collections/" + collection + "/points/count", Map.of("exact", true)).path("result").path("count").asInt();
            if (count != indexedCatalog.all().size()) throw new IllegalStateException("Report index has unexpected points");
            ready = true;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            close();
            throw new IllegalStateException("Local report retrieval initialization failed", failure);
        }
    }
    @Override public void add(List<Document> documents) {
        if (embedding == null || process == null || !process.isAlive()) throw new IllegalStateException("Report index is not initialized");
        var points = new ArrayList<Object>();
        for (var document : documents) {
            var row = indexedCatalog.require(document.getId());
            if (!row.text().equals(document.getText())) throw new IllegalArgumentException("Only canonical report text can be indexed");
            points.add(Map.of("id", row.id(), "vector", embedding.embed(row.text()), "payload", Map.of("symbol", row.symbol(),
                    "reportYear", row.reportYear(), "reportVersion", row.reportVersion(), "textSha256", row.textSha256(),
                    "status", "AVAILABLE", "embeddingVersion", LocalReportEmbedding.VERSION)));
        }
        request("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", points));
    }
    // Report publication/removal belongs to the release catalog, never to model tools.
    @Override public void delete(List<String> ids) { throw new UnsupportedOperationException("Change the reviewed report catalog and rebuild instead"); }
    @Override public void delete(Filter.Expression expression) { throw new UnsupportedOperationException("Change the reviewed report catalog and rebuild instead"); }

    private JsonNode request(String method, String path, Object body) {
        try {
            if (process != null && !process.isAlive()) throw new IllegalStateException("Owned Qdrant has stopped");
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .header("Content-Type", "application/json");
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
                if (response.statusCode() != 200 || bytes.length > 2 * 1024 * 1024) throw new IllegalStateException("Local report store request failed");
                return json.readTree(bytes);
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Local report store unavailable", failure);
        }
    }
    private void recoverOrphan() throws Exception {
        Path record = directory.resolve("owned-process.json");
        if (!Files.isRegularFile(record) || Files.size(record) > 4096) return;
        var saved = json.readTree(Files.readAllBytes(record));
        var old = ProcessHandle.of(saved.path("pid").asLong()).filter(ProcessHandle::isAlive);
        if (old.isEmpty()) return;
        var info = old.get().info();
        if (!info.startInstant().map(time -> time.toString().equals(saved.path("startedAt").asString())).orElse(false)) return;
        boolean parentAlive = ProcessHandle.of(saved.path("parentPid").asLong()).filter(ProcessHandle::isAlive)
                .map(parent -> parent.info().startInstant().map(time -> time.toString().equals(saved.path("parentStartedAt").asString())).orElse(true)).orElse(false);
        if (parentAlive || !binary.toString().equals(saved.path("binary").asString())
                || !info.startInstant().map(time -> time.toString().equals(saved.path("startedAt").asString())).orElse(false)
                || !info.command().map(command -> Path.of(command).equals(binary)).orElse(false)
                || !directory.toString().equals(saved.path("dataDirectory").asString()) || saved.path("port").asInt() != port)
            throw new IllegalStateException("Previous report process ownership could not be verified");
        old.get().destroy();
        try { old.get().onExit().get(2, java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) { old.get().destroyForcibly(); old.get().onExit().get(2, java.util.concurrent.TimeUnit.SECONDS); }
        waitForOwnedPortRelease();
    }
    private void waitForOwnedPortRelease() throws InterruptedException {
        // Windows may acknowledge one last loopback connection just after process termination.
        for (int i = 0; i < 20; i++) {
            try (var connection = new Socket()) { connection.connect(new InetSocketAddress("127.0.0.1", port), 100); }
            catch (java.io.IOException released) { return; }
            Thread.sleep(50);
        }
    }
    @PreDestroy @Override public synchronized void close() {
        ready = false;
        if (embedding != null) { try { embedding.close(); } catch (OrtException ignored) { /* release owned process below */ } finally { embedding = null; } }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS); }
                waitForOwnedPortRelease();
            }
            catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            process = null;
        }
        try { if (ownership != null && ownership.isValid()) ownership.release(); }
        catch (java.io.IOException ignored) { /* channel close also releases the owned lock */ }
        finally {
            ownership = null;
            try { if (lockChannel != null) lockChannel.close(); } catch (java.io.IOException ignored) { /* no shared process was stopped */ }
            lockChannel = null;
        }
    }
}
