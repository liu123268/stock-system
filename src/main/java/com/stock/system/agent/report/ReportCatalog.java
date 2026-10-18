package com.stock.system.agent.report;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** The release-owned catalog is authoritative. Vector payloads cannot publish or restore sources. */
@Component
public class ReportCatalog {
    private final Map<String, ReportPassage> passages;
    private final String fingerprint;

    public ReportCatalog() throws IOException {
        this(new ClassPathResource("reports/reviewed-passages.json").getContentAsByteArray());
    }

    ReportCatalog(byte[] content) {
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

    public String fingerprint() { return fingerprint; }
    public List<ReportPassage> all() { return List.copyOf(passages.values()); }
    public List<ReportPassage> forSymbol(String symbol) { return passages.values().stream().filter(row -> row.symbol().equals(symbol)).toList(); }
    public boolean matches(ReportPassage snapshot) { return snapshot != null && snapshot.equals(passages.get(snapshot.id())); }
    public ReportPassage require(String id) {
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
