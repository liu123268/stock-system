package com.stock.system.stock;

import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.support.MySqlIntegrationTest;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 等价性进入普通回归；规模实验必须显式启用，只在隔离测试库提交本轮 fixture。 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "stock-system.agent.budget-yuan=0"})
class WorkspaceStockListQueryIntegrationTest extends MySqlIntegrationTest {
    @Autowired private DataSource dataSource;
    @Autowired private SqlSessionFactory sessions;
    @Autowired private WorkspaceStockMapper mapper;
    @Autowired private ObjectMapper json;

    @Test
    void scopedStatisticsPreserveCompleteRowsCountsLatestOpinionAndOrdering() throws Exception {
        try (Connection connection = dataSource.getConnection(); Fixture fixture = new Fixture(connection)) {
            fixture.seedTarget(3, 7);
            fixture.addBackgroundOpinions(21);
            connection.commit();
            Query before = before();
            Query current = current(fixture.targetWorkspace);
            var expected = rows(connection, before, fixture.targetWorkspace);
            var actual = rows(connection, current, fixture.targetWorkspace);
            assertThat(actual).containsExactlyElementsOf(expected);
            assertThat(actual).hasSize(3).allSatisfy(row -> {
                assertThat(((Number) row.get("opinion_count")).intValue()).isEqualTo(7);
                assertThat(((Number) row.get("participant_count")).intValue()).isEqualTo(3);
                long cycle = ((Number) row.get("research_cycle_id")).longValue();
                assertThat(((Number) row.get("latest_opinion_id")).longValue()).isLessThan(fixture.newestWithdrawn.get(cycle));
            });
            assertThat(actual.get(0).get("attention_level")).isEqualTo("FOCUS");
            assertThat(mapper.selectActiveList(fixture.targetWorkspace)).hasSize(3).allSatisfy(row -> {
                assertThat(row.getOpinionCount()).isEqualTo(7);
                assertThat(row.getParticipantCount()).isEqualTo(3);
                assertThat(row.getLatestOpinionId()).isNotNull();
            });
            long emptyWorkspace = fixture.workspace("empty");
            connection.commit();
            assertThat(rows(connection, before, emptyWorkspace)).isEmpty();
            assertThat(rows(connection, current, emptyWorkspace)).isEmpty();
        }
    }

    @Test
    void fixtureCleanupAlsoRunsWhenAnExceptionFollowsCommittedInserts() throws Exception {
        assertThatThrownBy(() -> {
            try (Connection connection = dataSource.getConnection(); Fixture fixture = new Fixture(connection)) {
                fixture.seedTarget(1, 3);
                connection.commit();
                fixture.analyzeStatistics();
                throw new IllegalStateException("controlled failure after committed fixture");
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("controlled failure after committed fixture");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "STOCK_SQL_LIST_PERFORMANCE_PROBE", matches = "true")
    void measuresActualMappedQueryAgainstFrozenBaselineAtThreeDataScales() throws Exception {
        Path target = Path.of("target").toAbsolutePath().normalize();
        Path output = Path.of(System.getProperty("stock.sql-list-performance.output",
                "target/sql-list-performance-" + System.currentTimeMillis())).toAbsolutePath().normalize();
        assertThat(output.startsWith(target)).as("性能证据只写项目 target 内").isTrue();
        assertThat(Files.exists(output)).as("每次实验使用新目录，不能覆盖旧证据").isFalse();
        Files.createDirectories(output);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("passed", false);
        report.put("startedAt", Instant.now().toString());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("metric", "JDBC query plus full result consumption; milliseconds; nearest-rank percentiles");
        report.put("warmupPairs", 5);
        report.put("measuredPairs", 30);
        List<Map<String, Object>> stages = new ArrayList<>();
        report.put("stages", stages);
        Fixture fixture = null;
        try (Connection connection = dataSource.getConnection()) {
            // 排除并行业务 fixture 后才开始大批写入；不清空他人已有测试数据。
            assertThat(scalar(connection, "select count(*) from workspace")).as("规模实验需要空的业务测试库").isZero();
            try (Fixture owned = new Fixture(connection)) {
                fixture = owned;
                report.put("fixture", owned.marker);
                report.put("database", connection.getCatalog());
                report.put("databaseEnvironment", rows(connection, new Query("select version() as mysql_version, "
                        + "@@session.transaction_isolation as isolation_level, @@innodb_buffer_pool_size as buffer_pool_bytes", 0), 0));
                owned.seedTarget(10, 200);
                connection.commit();
                Query before = before();
                Query current = current(owned.targetWorkspace);
                Files.writeString(output.resolve("before.sql"), before.sql, StandardCharsets.UTF_8);
                Files.writeString(output.resolve("current.sql"), current.sql, StandardCharsets.UTF_8);
                report.put("beforeSqlSha256", sha256(before.sql));
                report.put("currentSqlSha256", sha256(current.sql));
                report.put("currentBindings", current.bindings);
                report.put("targetActiveStocks", 10);
                report.put("targetActiveOpinions", 2000);
                report.put("targetHistoricalOpinions", 400);
                for (int background : new int[]{0, 10_000, 100_000}) {
                    owned.addBackgroundOpinions(background);
                    connection.commit();
                    // ANALYZE TABLE 会提交事务，所以这里使用已提交且 finally 精确清理的 fixture。
                    owned.analyzeStatistics();
                    Map<String, Object> stage = measure(connection, before, current, owned.targetWorkspace);
                    stage.put("backgroundOpinions", background);
                    stage.put("totalFixtureOpinions", scalar(connection, "select count(*) from stock_opinion"));
                    stages.add(stage);
                    for (var variant : Map.of("before", before, "current", current).entrySet()) {
                        String name = background + "-" + variant.getKey();
                        var analyze = rows(connection, new Query("explain analyze " + variant.getValue().sql, variant.getValue().bindings), owned.targetWorkspace);
                        Files.writeString(output.resolve(name + "-analyze.txt"), analyze.get(0).values().iterator().next().toString(), StandardCharsets.UTF_8);
                        var estimated = rows(connection, new Query("explain format=json " + variant.getValue().sql, variant.getValue().bindings), owned.targetWorkspace);
                        Files.writeString(output.resolve(name + "-plan.json"), estimated.get(0).values().iterator().next().toString(), StandardCharsets.UTF_8);
                    }
                    System.out.println("SQL_PERFORMANCE_STAGE " + json.writeValueAsString(stage));
                }
                assertThat(mapper.selectActiveList(owned.targetWorkspace)).hasSize(10);
            }
            report.put("passed", true);
        } finally {
            report.put("cleanup", fixture == null ? Map.of() : fixture.cleanup);
            report.put("statisticsRestored", fixture != null && fixture.statisticsRestored);
            report.put("finishedAt", Instant.now().toString());
            Files.writeString(output.resolve("results.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
            System.out.println("SQL_PERFORMANCE_EVIDENCE " + output);
        }
    }

    private Query current(long workspace) {
        var bound = sessions.getConfiguration().getMappedStatement(WorkspaceStockMapper.class.getName() + ".selectActiveList").getBoundSql(workspace);
        return new Query(bound.getSql(), bound.getParameterMappings().size());
    }

    private Query before() throws Exception {
        try (var input = getClass().getResourceAsStream("/performance/workspace-stock-list-before.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int bindings = sql.split("#\\{workspaceId}", -1).length - 1;
            return new Query(sql.replace("#{workspaceId}", "?"), bindings);
        }
    }

    private Map<String, Object> measure(Connection connection, Query before, Query current, long workspace) throws Exception {
        var expected = rows(connection, before, workspace);
        assertThat(expected).hasSize(10);
        for (int index = 0; index < 5; index++) {
            assertThat(rows(connection, before, workspace)).containsExactlyElementsOf(expected);
            assertThat(rows(connection, current, workspace)).containsExactlyElementsOf(expected);
        }
        List<Double> oldTimes = new ArrayList<>(), newTimes = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            // AB/BA 交替，避免一条 SQL 总是先执行；每次结果均与同一基线全字段比较。
            if (index % 2 == 0) {
                sample(connection, before, workspace, expected, oldTimes);
                sample(connection, current, workspace, expected, newTimes);
            } else {
                sample(connection, current, workspace, expected, newTimes);
                sample(connection, before, workspace, expected, oldTimes);
            }
        }
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("equalRows", expected.size());
        stage.put("beforeMs", statistics(oldTimes));
        stage.put("currentMs", statistics(newTimes));
        stage.put("beforeSamplesMs", oldTimes);
        stage.put("currentSamplesMs", newTimes);
        return stage;
    }

    private void sample(Connection connection, Query query, long workspace, List<Map<String, Object>> expected, List<Double> times) throws Exception {
        long start = System.nanoTime();
        var actual = rows(connection, query, workspace);
        times.add((System.nanoTime() - start) / 1_000_000.0);
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private Map<String, Double> statistics(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        return Map.of("p50", sorted.get((int) Math.ceil(sorted.size() * 0.50) - 1),
                "p95", sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1));
    }

    private record Query(String sql, int bindings) {}

    private static List<Map<String, Object>> rows(Connection connection, Query query, long workspace) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query.sql)) {
            statement.setQueryTimeout(15);
            for (int index = 1; index <= query.bindings; index++) statement.setLong(index, workspace);
            try (ResultSet result = statement.executeQuery()) {
                var rows = new ArrayList<Map<String, Object>>();
                var metadata = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int index = 1; index <= metadata.getColumnCount(); index++) row.put(metadata.getColumnLabel(index), result.getObject(index));
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        return ((Number) rows(connection, new Query(sql, 0), 0).get(0).values().iterator().next()).longValue();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Fixture implements AutoCloseable {
        private static final String LOCK = "stock-system:sql-list-performance";
        private final Connection connection;
        private final String marker = "sp" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        private final List<Long> users = new ArrayList<>(), stocks = new ArrayList<>(), workspaces = new ArrayList<>(), trackedStocks = new ArrayList<>(), cycles = new ArrayList<>();
        private final Map<Long, Long> newestWithdrawn = new LinkedHashMap<>();
        private final Map<Long, LocalDateTime> cycleStarts = new LinkedHashMap<>();
        private final Map<String, Long> cleanup = new LinkedHashMap<>();
        private long targetWorkspace;
        private int opinionsPerCycle, backgroundRows, backgroundSpaces;
        private boolean statisticsUpdated, statisticsRestored;

        Fixture(Connection connection) throws SQLException {
            assertThat(connection.getCatalog()).isEqualTo("stock_system_test");
            assertThat(connection.getAutoCommit()).as("fixture 必须独占自己的连接，不能加入外层事务").isTrue();
            this.connection = connection;
            assertThat(scalar(connection, "select get_lock('" + LOCK + "', 0)")).as("已有列表实验正在运行").isEqualTo(1);
            connection.setAutoCommit(false);
        }

        void seedTarget(int stockCount, int opinionsPerCycle) throws SQLException {
            this.opinionsPerCycle = opinionsPerCycle;
            for (int index = 0; index < 3; index++) users.add(insert("insert into app_user (username,password_hash,nickname) values (?,?,?)", marker + "_" + index, "unused-fixture-hash", "fixture " + index));
            for (int index = 0; index < stockCount; index++) stocks.add(insert("insert into stock (code,name,exchange,currency,listing_status,source) values (?,?,'SSE','CNY','LISTED',?)", marker + index, "fixture " + index, marker));
            targetWorkspace = workspace("target");
            for (int index = 0; index < stockCount; index++) {
                long tracked = trackedStock(targetWorkspace, stocks.get(index));
                opinions(cycle(tracked, "ACTIVE", index), opinionsPerCycle);
                opinions(cycle(tracked, "ENDED", index), Math.min(20, opinionsPerCycle));
                opinions(cycle(tracked, "CANCELLED", index), Math.min(20, opinionsPerCycle));
            }
        }

        long workspace(String suffix) throws SQLException {
            long id = insert("insert into workspace (name,created_by_user_id,owner_user_id) values (?,?,?)", marker + "_" + suffix, users.get(0), users.get(0));
            workspaces.add(id);
            for (long user : users) insert("insert into workspace_member (workspace_id,user_id) values (?,?)", id, user);
            return id;
        }

        private long trackedStock(long workspace, long stock) throws SQLException {
            long id = insert("insert into workspace_stock (workspace_id,stock_id,first_added_by_user_id) values (?,?,?)", workspace, stock, users.get(0));
            trackedStocks.add(id);
            return id;
        }

        private long cycle(long tracked, String status, int index) throws SQLException {
            LocalDateTime start = LocalDateTime.of(status.equals("ACTIVE") ? 2026 : 2025, 1, 1, 9, 0).plusSeconds(index);
            long id = insert("insert into research_cycle (workspace_stock_id,status,attention_level,started_by_user_id,started_at,start_price,quote_time,quote_source,is_simulated,ended_at) values (?,?,?,?,?,100,?,'SQL_PERF_FIXTURE',true,?)",
                    tracked, status, index % 2 == 0 ? "FOCUS" : "NORMAL", users.get(0), Timestamp.valueOf(start), Timestamp.valueOf(start), status.equals("ACTIVE") ? null : Timestamp.valueOf(start.plusDays(1)));
            cycles.add(id);
            cycleStarts.put(id, start);
            return id;
        }

        private void opinions(long cycle, int count) throws SQLException {
            String sql = "insert into stock_opinion (research_cycle_id,author_user_id,type,stance,reason,horizon,created_at,withdrawn_at,withdrawn_by_user_id) values "
                    + String.join(",", Collections.nCopies(count, "(?,?,?,?,?,?,?,?,?)"));
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int binding = 1;
                for (int index = 0; index < count; index++) {
                    boolean withdrawn = index > 0 && (index == count - 1 || index % 17 == 0);
                    long author = users.get(index % users.size());
                    Timestamp created = Timestamp.valueOf(cycleStarts.get(cycle).plusHours(1).plusSeconds(index / 3));
                    for (Object value : new Object[]{cycle, author, index == 0 ? "INITIAL" : "UPDATE", "NEUTRAL", "fixture " + marker, "SHORT", created, withdrawn ? created : null, withdrawn ? author : null}) statement.setObject(binding++, value);
                }
                statement.executeUpdate();
            }
            newestWithdrawn.put(cycle, scalar(connection, "select max(id) from stock_opinion where research_cycle_id = " + cycle));
        }

        void addBackgroundOpinions(int count) throws SQLException {
            int block = stocks.size() * opinionsPerCycle;
            assertThat(count % block).isZero();
            while (backgroundRows < count) {
                long workspace = workspace("noise" + ++backgroundSpaces);
                for (int index = 0; index < stocks.size(); index++) {
                    opinions(cycle(trackedStock(workspace, stocks.get(index)), "ACTIVE", index), opinionsPerCycle);
                    backgroundRows += opinionsPerCycle;
                }
            }
        }

        private long insert(String sql, Object... values) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) { assertThat(keys.next()).isTrue(); return keys.getLong(1); }
            }
        }

        void analyzeStatistics() throws SQLException {
            statisticsUpdated = true;
            refreshStatistics();
        }

        private void refreshStatistics() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("analyze table app_user, stock, workspace, workspace_stock, research_cycle, stock_opinion");
            }
        }

        @Override public void close() throws SQLException {
            try {
                connection.rollback();
                delete("stock_opinion", "research_cycle_id", cycles);
                delete("research_cycle", "id", cycles);
                delete("workspace_stock", "id", trackedStocks);
                delete("workspace_member", "workspace_id", workspaces);
                delete("workspace", "id", workspaces);
                delete("stock", "id", stocks);
                delete("app_user", "id", users);
                connection.commit();
                if (statisticsUpdated) {
                    refreshStatistics();
                    statisticsRestored = true;
                }
                remaining("stock_opinion", "research_cycle_id", cycles);
                remaining("research_cycle", "id", cycles);
                remaining("workspace_stock", "id", trackedStocks);
                remaining("workspace_member", "workspace_id", workspaces);
                remaining("workspace", "id", workspaces);
                remaining("stock", "id", stocks);
                remaining("app_user", "id", users);
                assertThat(cleanup.values()).allMatch(value -> value == 0);
                System.out.println("SQL_FIXTURE_CLEANUP " + marker + " " + cleanup + " statisticsRestored=" + statisticsRestored);
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            } finally {
                try (Statement statement = connection.createStatement()) { statement.execute("select release_lock('" + LOCK + "')"); }
                connection.setAutoCommit(true);
            }
        }

        private void delete(String table, String column, List<Long> ids) throws SQLException {
            if (ids.isEmpty()) return;
            try (PreparedStatement statement = idStatement("delete from " + table, column, ids)) { statement.executeUpdate(); }
        }

        private void remaining(String table, String column, List<Long> ids) throws SQLException {
            if (ids.isEmpty()) { cleanup.put(table, 0L); return; }
            try (PreparedStatement statement = idStatement("select count(*) from " + table, column, ids); ResultSet result = statement.executeQuery()) {
                result.next(); cleanup.put(table, result.getLong(1));
            }
        }

        private PreparedStatement idStatement(String prefix, String column, List<Long> ids) throws SQLException {
            // 表／列只来自上面的固定清单，所有本轮生成 ID 仍使用值占位符。
            PreparedStatement statement = connection.prepareStatement(prefix + " where " + column + " in (" + String.join(",", Collections.nCopies(ids.size(), "?")) + ")");
            for (int index = 0; index < ids.size(); index++) statement.setLong(index + 1, ids.get(index));
            return statement;
        }
    }
}
