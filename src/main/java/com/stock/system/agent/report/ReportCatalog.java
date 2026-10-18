package com.stock.system.agent.report;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Reviewed local publications are authoritative. Vector payloads cannot publish or restore sources. */
@Component
public class ReportCatalog {
    private final Map<String, ReportPassage> passages;
    private final String fingerprint;
    private ReportLibraryStore library;

    public ReportCatalog() throws IOException {
        this(new ClassPathResource("reports/reviewed-passages.json").getContentAsByteArray());
    }

    @Autowired
    public ReportCatalog(@Value("${stock-system.agent.reports.catalog-directory:.local/report-rag/library}") String directory,
                         @Value("${stock-system.agent.reports.enabled:false}") boolean enabled) throws IOException {
        this();
        Path path = Path.of(directory);
        if (enabled || Files.isRegularFile(path.resolve("library.json"))) library = new ReportLibraryStore(path, this.installed());
    }

    public ReportCatalog installed() { return ReportLibraryStore.catalog(List.copyOf(passages.values())); }
    public ReportCatalog snapshot() { return library == null ? this : library.active(); }
    public ReportLibraryStore library() {
        if (library == null) throw new IllegalStateException("Report maintenance storage is not enabled");
        return library;
    }
    public List<ReportLibraryStore.Status> statuses(String symbol) { return library == null ? List.of() : library.statuses(symbol); }

    public ReportCatalog(byte[] content) {
        var catalog = JsonMapper.builder().build().readValue(content, Catalog.class);
        if (catalog.version() == null || catalog.passages() == null || catalog.passages().size() > 200)
            throw new IllegalArgumentException("Invalid reviewed report catalog");
        var rows = new LinkedHashMap<String, ReportPassage>();
        for (ReportPassage row : catalog.passages()) {
            validate(row);
            if (rows.put(row.id(), row) != null) throw new IllegalArgumentException("Duplicate report passage");
        }
        passages = Collections.unmodifiableMap(rows);
        fingerprint = sha256(content);
    }

    private static void validate(ReportPassage row) {
        URI uri = URI.create(row.sourceUrl());
        if (!"https".equals(uri.getScheme()) || !Set.of("static.cninfo.com.cn", "static.www.tencent.com").contains(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
                || !row.reportVersion().matches("[a-f0-9]{64}") || !row.symbol().matches("[0-9]{6}\\.(SH|SZ)|[0-9]{1,5}\\.HK")
                || !"AVAILABLE".equals(row.status()) || !"REPORT".equals(row.corpus())
                || !"UNICODE_CODE_POINT".equals(row.charUnit()) || !"remove-whitespace-v1".equals(row.normalization())
                || row.pdfPage() < 1 || row.charStart() < 0 || row.charEnd() <= row.charStart()
                || row.text().isBlank() || row.text().codePointCount(0, row.text().length()) > 320
                || row.charEnd() - row.charStart() != row.text().codePointCount(0, row.text().length())
                || !sha256(row.text().getBytes(StandardCharsets.UTF_8)).equals(row.textSha256()))
            throw new IllegalArgumentException("Invalid reviewed report identity or position");
        UUID.fromString(row.id());
    }

    public String fingerprint() { return library == null ? fingerprint : snapshot().fingerprint(); }
    public List<ReportPassage> all() { return library == null ? List.copyOf(passages.values()) : snapshot().all(); }
    public List<ReportPassage> forSymbol(String symbol) { return all().stream().filter(row -> row.symbol().equals(symbol)).toList(); }
    public boolean matches(ReportPassage snapshot) { return library == null ? snapshot != null && snapshot.equals(passages.get(snapshot.id())) : library.matches(snapshot); }
    public boolean matchesCurrent(ReportPassage value) { return snapshot().matches(value); }
    public ReportPassage require(String id) {
        if (library != null) return snapshot().require(id);
        ReportPassage row = passages.get(id);
        if (row == null) throw new IllegalArgumentException("Unknown report passage");
        return row;
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private record Catalog(String version, List<ReportPassage> passages) {}
}
