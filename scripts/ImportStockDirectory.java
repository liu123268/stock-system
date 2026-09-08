import com.stock.system.stock.directory.StockDirectoryImporter;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Properties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicit one-shot command; no HTTP server, no application bootstrap or account creation. */
class ImportStockDirectory {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].matches("dev|test") || !args[1].matches("inspect|verify|apply")
                || (args[1].equals("apply") && !args[0].equals("dev"))
                || (args[1].equals("verify") && !args[0].equals("test"))) {
            throw new IllegalArgumentException("Usage: dev inspect | test inspect | test verify <json> | dev apply <json>");
        }
        var props = new Properties();
        for (String file : new String[]{"application.properties", "application-" + args[0] + ".properties"}) {
            try (var reader = Files.newBufferedReader(Path.of("src/main/resources", file), StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        }
        var values = new HashMap<String, Object>();
        props.forEach((k, v) -> values.put(k.toString(), v));
        var env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("project", values));
        String url = env.getRequiredProperty("spring.datasource.url");
        String expected = args[0].equals("dev") ? "stock_system" : "stock_system_test";
        // Refuse redirects to unrelated catalogs before opening a connection.
        if (!url.matches("jdbc:mysql://localhost:3306/" + expected + "(?:\\?.*)?")) {
            throw new IllegalStateException("Unexpected database URL; review configuration");
        }
        var ds = new DriverManagerDataSource(url, env.getRequiredProperty("spring.datasource.username"),
                env.getRequiredProperty("spring.datasource.password"));
        try (Connection c = ds.getConnection()) {
            if (!expected.equals(c.getCatalog())) throw new IllegalStateException("JDBC catalog mismatch");
            System.out.println("DATABASE=" + c.getCatalog() + " STOCKS_BEFORE=" + count(c));
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
                while (r.next()) System.out.println("MIGRATION=" + r.getString(1) + " SUCCESS=" + r.getBoolean(2));
            }
        }
        if (args[1].equals("inspect")) return;
        var importer = new StockDirectoryImporter();
        var directory = importer.read(Path.of(args[2]));
        Flyway.configure().dataSource(ds).cleanDisabled(true).load().migrate();
        try (Connection c = ds.getConnection()) {
            if (!expected.equals(c.getCatalog())) throw new IllegalStateException("JDBC catalog mismatch");
            long before = count(c);
            c.setAutoCommit(false);
            try {
                var originalIds = new HashMap<String, Long>();
                var beforeRows = new ArrayList<Object>();
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM stock")) {
                    while (r.next()) {
                        originalIds.put(r.getString("code"), r.getLong("id"));
                        var row = new LinkedHashMap<String, Object>();
                        for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                            row.put(r.getMetaData().getColumnName(i), r.getString(i));
                        }
                        beforeRows.add(row);
                    }
                }
                if (args[1].equals("apply")) {
                    Path backup = Path.of("target/market-directory-probe/stock-before-" + System.currentTimeMillis() + ".json");
                    Files.createDirectories(backup.getParent());
                    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(backup.toFile(), beforeRows);
                }
                importer.importRows(c, directory);
                var expectedRows = new HashMap<String, StockDirectoryImporter.Entry>();
                directory.stocks().forEach(row -> expectedRows.put(row.code(), row));
                int matched = 0;
                try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM stock")) {
                    while (r.next()) {
                        String code = r.getString("code");
                        if (originalIds.containsKey(code) && originalIds.get(code) != r.getLong("id")) {
                            throw new IllegalStateException("Existing ID changed");
                        }
                        var row = expectedRows.get(code);
                        if (row == null) continue;
                        if (!row.name().equals(r.getString("name")) || !row.exchange().equals(r.getString("exchange"))
                                || !row.currency().equals(r.getString("currency")) || !row.source().equals(r.getString("source"))
                                || !row.searchAliases().equals(r.getString("search_aliases"))) {
                            throw new IllegalStateException("Imported field mismatch: " + code);
                        }
                        matched++;
                    }
                }
                if (matched != directory.stocks().size()) throw new IllegalStateException("Imported count mismatch");
                System.out.println("MATCHED_FIELDS=" + matched + " PRESERVED_EXISTING_IDS=" + originalIds.size());
                long first = count(c);
                if (args[1].equals("verify")) {
                    importer.importRows(c, directory);
                    if (count(c) != first) throw new IllegalStateException("Import is not idempotent");
                    c.rollback();
                    if (count(c) != before) throw new IllegalStateException("Rollback count mismatch");
                    System.out.println("VERIFIED_ROWS=" + directory.stocks().size() + " ROLLBACK_COUNT=" + count(c));
                } else {
                    c.commit();
                    System.out.println("COMMITTED_ROWS=" + directory.stocks().size() + " STOCKS_AFTER=" + count(c));
                    try (var s = c.createStatement(); ResultSet r = s.executeQuery("SELECT exchange, COUNT(*) FROM stock GROUP BY exchange")) {
                        while (r.next()) System.out.println(r.getString(1) + "=" + r.getLong(2));
                    }
                }
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }
    private static long count(Connection c) throws Exception {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM stock")) {
            r.next(); return r.getLong(1);
        }
    }
}
