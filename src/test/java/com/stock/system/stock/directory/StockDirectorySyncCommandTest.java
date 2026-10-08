package com.stock.system.stock.directory;

import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class StockDirectorySyncCommandTest extends MySqlIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    private final String label = "同步验收" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private boolean ownsFixtures;

    @BeforeEach
    void rejectExistingFixtureIdentities() {
        assertThat(jdbc.queryForObject("select count(*) from stock where code in ('920990.BJ','920991.BJ')",
                Integer.class)).isZero();
        ownsFixtures = true;
    }

    private StockDirectoryImporter.Directory snapshot(String generatedAt, String name) {
        return new StockDirectoryImporter.Directory(generatedAt, "sync fixture", List.of(
                new StockDirectoryImporter.Entry("920990.BJ", name, "BSE", "CNY", "CNINFO_DIRECTORY",
                        "同步验收", "tbyssc", "UNKNOWN")));
    }

    @AfterEach
    void cleanOnlyOwnedFixtures() {
        if (!ownsFixtures) return;
        jdbc.update("delete from stock where code in ('920990.BJ','920991.BJ') and name like ?", label + "%");
        assertThat(jdbc.queryForObject("select count(*) from stock where name like ?", Integer.class, label + "%"))
                .isZero();
    }

    @Test
    void appliedSnapshotIsIdempotentPreservesStatusAndDoesNotDeleteAbsentRows() throws Exception {
        jdbc.update("insert into stock (code,name,exchange,currency,listing_status,source) "
                + "values ('920990.BJ',?,'BSE','CNY','LISTED','CNINFO_DIRECTORY'),"
                + "('920991.BJ',?,'BSE','CNY','UNKNOWN','CNINFO_DIRECTORY')", label + "旧名", label + "缺席");
        Long id = jdbc.queryForObject("select id from stock where code='920990.BJ'", Long.class);
        var directory = snapshot(Instant.now().toString(), label + "新名");
        var first = StockDirectorySyncCommand.execute(dataSource, directory, "stock_system_test", false);
        StockDirectorySyncCommand.execute(dataSource, directory, "stock_system_test", false);
        assertThat(first).containsEntry("status", "APPLIED").containsEntry("rows", 1);
        assertThat(jdbc.queryForObject("select id from stock where code='920990.BJ'", Long.class)).isEqualTo(id);
        assertThat(jdbc.queryForObject("select listing_status from stock where code='920990.BJ'", String.class))
                .isEqualTo("LISTED");
        assertThat(jdbc.queryForObject("select count(*) from stock where code='920991.BJ'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void verificationRollsBackBothImports() throws Exception {
        var receipt = StockDirectorySyncCommand.execute(dataSource,
                snapshot(Instant.now().toString(), label + "回滚"), "stock_system_test", true);
        assertThat(receipt).containsEntry("status", "VERIFIED");
        assertThat(jdbc.queryForObject("select count(*) from stock where code='920990.BJ'", Integer.class)).isZero();
    }

    @Test
    void olderSnapshotCannotOverwriteNewerDirectory() {
        jdbc.update("insert into stock (code,name,exchange,currency,listing_status,source,last_synced_at) "
                + "values ('920990.BJ',?,'BSE','CNY','UNKNOWN','CNINFO_DIRECTORY',now(3))", label + "较新");
        assertThatThrownBy(() -> StockDirectorySyncCommand.execute(dataSource,
                snapshot("2020-01-01T00:00:00Z", label + "旧快照"), "stock_system_test", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Stale snapshot");
        assertThat(jdbc.queryForObject("select name from stock where code='920990.BJ'", String.class))
                .isEqualTo(label + "较新");
    }

    @Test
    void concurrentRunCannotWriteWhileAdvisoryLockIsOwned() throws Exception {
        try (var owner = dataSource.getConnection(); var statement = owner.createStatement()) {
            statement.executeQuery("select get_lock('stock-system:directory-sync:stock_system_test',0)").close();
            try {
                assertThatThrownBy(() -> StockDirectorySyncCommand.execute(dataSource,
                        snapshot(Instant.now().toString(), label + "竞争"), "stock_system_test", false))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Directory sync already running");
                assertThat(jdbc.queryForObject("select count(*) from stock where code='920990.BJ'", Integer.class))
                        .isZero();
            } finally {
                statement.executeQuery("select release_lock('stock-system:directory-sync:stock_system_test')").close();
            }
        }
    }

    @Test
    void wrongCatalogFailsBeforeAnyWrite() {
        assertThatThrownBy(() -> StockDirectorySyncCommand.execute(dataSource,
                snapshot(Instant.now().toString(), label + "错库"), "another_catalog", false))
                .isInstanceOf(IllegalStateException.class).hasMessage("Catalog mismatch");
    }
}
