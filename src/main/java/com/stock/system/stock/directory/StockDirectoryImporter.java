package com.stock.system.stock.directory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the entire batch before writing; the caller owns commit/rollback. */
public final class StockDirectoryImporter {
    private static final Map<String, String> SUFFIX = Map.of("SSE", "SH", "SZSE", "SZ", "BSE", "BJ", "HKEX", "HK");
    private static final Map<String, String> SOURCES = Map.of("SSE", "SSE_DIRECTORY", "SZSE", "SZSE_DIRECTORY",
            "BSE", "CNINFO_DIRECTORY", "HKEX", "HKEX_DIRECTORY");

    public record Entry(String code, String name, String exchange, String currency,
                        String source, String searchAliases, String pinyinInitials,
                        String listingStatus) { }
    public record Directory(String generatedAt, String coverageNote, List<Entry> stocks) { }

    public Directory read(Path path) throws java.io.IOException {
        Directory directory = new ObjectMapper().readValue(path.toFile(), Directory.class);
        validate(directory);
        return directory;
    }

    public void validate(Directory directory) {
        if (directory == null || directory.stocks() == null || directory.stocks().isEmpty()) {
            throw new IllegalArgumentException("Empty directory");
        }
        OffsetDateTime.parse(directory.generatedAt());
        Set<String> codes = new HashSet<>();
        for (Entry row : directory.stocks()) {
            if (row == null || row.exchange() == null || !SUFFIX.containsKey(row.exchange())) {
                throw new IllegalArgumentException("Unknown exchange");
            }
            int width = "HKEX".equals(row.exchange()) ? 5 : 6;
            if (row.code() == null || !row.code().matches("[0-9]{" + width + "}\\." + SUFFIX.get(row.exchange()))
                    || !codes.add(row.code()) || row.name() == null || row.name().isBlank()
                    || row.name().codePointCount(0, row.name().length()) > 50
                    || row.searchAliases() == null || row.searchAliases().codePointCount(0, row.searchAliases().length()) > 200
                    || row.pinyinInitials() == null || !row.pinyinInitials().matches("[a-z]{1,50}")
                    || !("HKEX".equals(row.exchange()) ? "HKD" : "CNY").equals(row.currency())
                    || !SOURCES.get(row.exchange()).equals(row.source()) || !"UNKNOWN".equals(row.listingStatus())) {
                throw new IllegalArgumentException("Invalid or duplicate directory entry: " + row.code());
            }
        }
    }

    public int importRows(Connection connection, Directory directory) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalStateException("Explicit transaction required");
        validate(directory);
        LocalDateTime syncedAt = OffsetDateTime.parse(directory.generatedAt())
                .atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
        // Keep identifiers and research links. Absence never deletes or delists a stock.
        // Previously verified status survives a directory refresh; Mock status does not.
        String sql = """
                INSERT INTO stock (code, name, exchange, currency, listing_status, source,
                                   search_aliases, pinyin_initials, last_synced_at)
                VALUES (?, ?, ?, ?, 'UNKNOWN', ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    name=VALUES(name), exchange=VALUES(exchange), currency=VALUES(currency),
                    listing_status=IF(source='LOCAL_MOCK', 'UNKNOWN', listing_status),
                    source=VALUES(source), search_aliases=VALUES(search_aliases),
                    pinyin_initials=VALUES(pinyin_initials), last_synced_at=VALUES(last_synced_at)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int batch = 0;
            for (Entry row : directory.stocks()) {
                statement.setString(1, row.code()); statement.setString(2, row.name());
                statement.setString(3, row.exchange()); statement.setString(4, row.currency());
                statement.setString(5, row.source()); statement.setString(6, row.searchAliases());
                statement.setString(7, row.pinyinInitials());
                statement.setTimestamp(8, Timestamp.valueOf(syncedAt));
                statement.addBatch();
                if (++batch % 500 == 0) statement.executeBatch();
            }
            if (batch % 500 != 0) statement.executeBatch();
        }
        return directory.stocks().size();
    }

    /** Backfills only the derived search field and keeps directory sync metadata unchanged. */
    public int backfillPinyinInitials(Connection connection, Directory directory) throws SQLException {
        if (connection.getAutoCommit()) throw new IllegalStateException("Explicit transaction required");
        validate(directory);
        String sql = """
                UPDATE stock
                SET pinyin_initials = ?
                WHERE code = ? AND source <> 'LOCAL_MOCK'
                """;
        int matched = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int batch = 0;
            for (Entry row : directory.stocks()) {
                statement.setString(1, row.pinyinInitials());
                statement.setString(2, row.code());
                statement.addBatch();
                if (++batch % 500 == 0) matched += countMatched(statement.executeBatch());
            }
            if (batch % 500 != 0) matched += countMatched(statement.executeBatch());
        }
        if (matched != directory.stocks().size()) {
            throw new IllegalStateException("Pinyin backfill did not match every trusted directory row");
        }
        return matched;
    }

    private int countMatched(int[] counts) {
        int matched = 0;
        for (int count : counts) {
            if (count == java.sql.Statement.SUCCESS_NO_INFO) {
                matched++;
            } else if (count > 0) {
                matched += count;
            }
        }
        return matched;
    }
}
