package com.stock.system.stock.directory;

import tools.jackson.databind.ObjectMapper;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;

/** Reuses the directory importer without starting HTTP, SDK/CLI, or account services. */
public final class StockDirectorySyncCommand {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[0].matches("test|dev|prod")
                    || !("test".equals(args[0]) ? "verify" : "apply").equals(args[1])) {
                throw new IllegalArgumentException("Use test verify, dev apply or prod apply; JSON on stdin");
            }
            byte[] payload = System.in.readNBytes(10 * 1024 * 1024 + 1);
            if (payload.length > 10 * 1024 * 1024) throw new IllegalArgumentException("Directory too large");
            var directory = JSON.readValue(payload, StockDirectoryImporter.Directory.class);
            var dataSource = dataSource(args[0]);
            String catalog = "test".equals(args[0]) ? "stock_system_test" : "stock_system";
            Map<String, Object> receipt = execute(dataSource, directory, catalog, "verify".equals(args[1]));
            receipt.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
            System.out.println(JSON.writeValueAsString(receipt));
        } catch (Exception failure) {
            // Never print a JDBC URL, password, provider payload or raw exception.
            System.out.println("{\"status\":\"FAILED\",\"errorType\":\"" + failure.getClass().getSimpleName() + "\"}");
            System.exit(1);
        }
    }

    static Map<String, Object> execute(DataSource dataSource, StockDirectoryImporter.Directory directory,
                                       String expectedCatalog, boolean verify) throws Exception {
        Instant generated = new StockDirectoryImporter().validate(directory).toInstant();
        if (generated.isAfter(Instant.now().plusSeconds(300))) throw new IllegalArgumentException("Future snapshot");
        try (Connection connection = dataSource.getConnection()) {
            if (!expectedCatalog.equals(connection.getCatalog())) throw new IllegalStateException("Catalog mismatch");
            String lock = "stock-system:directory-sync:" + expectedCatalog;
            try (var statement = connection.prepareStatement("select get_lock(?, 0)")) {
                statement.setString(1, lock);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != 1) throw new IllegalStateException("Directory sync already running");
                }
            }
            try {
                connection.setAutoCommit(false);
                try {
                    try (var statement = connection.createStatement();
                         var rows = statement.executeQuery("select max(last_synced_at) from stock where source <> 'LOCAL_MOCK'")) {
                        rows.next();
                        Timestamp latest = rows.getTimestamp(1);
                        if (latest != null && generated.isBefore(latest.toLocalDateTime()
                                .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant())) {
                            throw new IllegalArgumentException("Stale snapshot");
                        }
                    }
                    int processed = new StockDirectoryImporter().importRows(connection, directory);
                    if (verify) {
                        new StockDirectoryImporter().importRows(connection, directory);
                        connection.rollback();
                    } else {
                        connection.commit();
                    }
                    var receipt = new HashMap<String, Object>();
                    receipt.put("status", verify ? "VERIFIED" : "APPLIED");
                    receipt.put("database", expectedCatalog);
                    receipt.put("rows", processed);
                    receipt.put("generatedAt", directory.generatedAt());
                    return receipt;
                } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                }
            } finally {
                try (var statement = connection.prepareStatement("select release_lock(?)")) {
                    statement.setString(1, lock);
                    statement.executeQuery().close();
                }
            }
        }
    }

    private static DataSource dataSource(String profile) throws Exception {
        var properties = new Properties();
        for (String name : new String[]{"application.properties", "application-" + profile + ".properties"}) {
            try (var stream = StockDirectorySyncCommand.class.getClassLoader().getResourceAsStream(name)) {
                if (stream == null) throw new IllegalStateException("Missing runtime configuration");
                properties.load(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8));
            }
        }
        if ("prod".equals(profile)) {
            Path external = Path.of("/app/config/application-prod.properties");
            if (Files.exists(external)) {
                try (var reader = Files.newBufferedReader(external)) { properties.load(reader); }
            }
        }
        var values = new HashMap<String, Object>();
        properties.forEach((key, value) -> values.put(key.toString(), value));
        if ("prod".equals(profile)) {
            values.put("mysql_app_password", Files.readString(Path.of("/run/secrets/mysql_app_password")).strip());
        }
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("directory", values));
        String url = environment.getRequiredProperty("spring.datasource.url");
        String catalog = "test".equals(profile) ? "stock_system_test" : "stock_system";
        if (!url.matches("jdbc:mysql://[^/]+/" + catalog + "(?:\\?.*)?")) {
            throw new IllegalStateException("Unexpected database URL");
        }
        return new DriverManagerDataSource(url, environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
    }
}
