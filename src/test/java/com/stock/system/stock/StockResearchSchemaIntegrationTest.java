package com.stock.system.stock;

import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class StockResearchSchemaIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterTransaction
    void schemaConstraintFixturesAreRolledBack() {
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock WHERE source = 'SCHEMA_TEST'",
                Integer.class
        );
        assertThat(remaining).isZero();
    }

    @Test
    void migrationCreatesTheFourStockResearchTables() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                        SELECT table_name
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE()
                          AND table_name IN ('stock', 'workspace_stock', 'research_cycle', 'stock_opinion')
                        ORDER BY table_name
                        """,
                String.class
        );

        assertThat(tables).containsExactly("research_cycle", "stock", "stock_opinion", "workspace_stock");
    }

    @Test
    void uniqueConstraintsProtectWorkspaceStockActiveCycleAndInitialOpinion() {
        Fixture fixture = createFixture();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO workspace_stock (workspace_id, stock_id, first_added_by_user_id) VALUES (?, ?, ?)",
                fixture.workspaceId(), fixture.stockId(), fixture.userId()
        )).isInstanceOf(DuplicateKeyException.class);

        long firstCycleId = insertActiveCycle(fixture.workspaceStockId(), fixture.userId());

        assertThatThrownBy(() -> insertActiveCycle(fixture.workspaceStockId(), fixture.userId()))
                .isInstanceOf(DuplicateKeyException.class);

        insertInitialOpinion(firstCycleId, fixture.userId());

        assertThatThrownBy(() -> insertInitialOpinion(firstCycleId, fixture.userId()))
                .isInstanceOf(DuplicateKeyException.class);

        jdbcTemplate.update(
                "UPDATE research_cycle SET status = 'ENDED', ended_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), firstCycleId
        );
        long nextCycleId = insertActiveCycle(fixture.workspaceStockId(), fixture.userId());

        assertThat(nextCycleId).isNotEqualTo(firstCycleId);
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String username = "schema_" + suffix;
        String workspaceName = "Schema " + suffix;
        String stockCode = suffix.toUpperCase() + ".SH";

        jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash, nickname) VALUES (?, ?, ?)",
                username, "schema-test-hash", "SchemaTest"
        );
        long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username
        );

        jdbcTemplate.update(
                "INSERT INTO workspace (name, created_by_user_id, owner_user_id) VALUES (?, ?, ?)",
                workspaceName, userId, userId
        );
        long workspaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace WHERE name = ?", Long.class, workspaceName
        );

        jdbcTemplate.update(
                """
                        INSERT INTO stock (code, name, exchange, currency, listing_status, source)
                        VALUES (?, '迁移测试股票', 'SSE', 'CNY', 'LISTED', 'SCHEMA_TEST')
                        """,
                stockCode
        );
        long stockId = jdbcTemplate.queryForObject(
                "SELECT id FROM stock WHERE code = ?", Long.class, stockCode
        );

        jdbcTemplate.update(
                "INSERT INTO workspace_stock (workspace_id, stock_id, first_added_by_user_id) VALUES (?, ?, ?)",
                workspaceId, stockId, userId
        );
        long workspaceStockId = jdbcTemplate.queryForObject(
                "SELECT id FROM workspace_stock WHERE workspace_id = ? AND stock_id = ?",
                Long.class, workspaceId, stockId
        );

        return new Fixture(userId, workspaceId, stockId, workspaceStockId);
    }

    private long insertActiveCycle(long workspaceStockId, long userId) {
        jdbcTemplate.update(
                """
                        INSERT INTO research_cycle (
                            workspace_stock_id, started_by_user_id, start_price,
                            quote_time, quote_source, is_simulated
                        ) VALUES (?, ?, ?, ?, 'SCHEMA_TEST', TRUE)
                        """,
                workspaceStockId,
                userId,
                new BigDecimal("10.2500"),
                Timestamp.valueOf(LocalDateTime.now())
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM research_cycle WHERE workspace_stock_id = ? AND status = 'ACTIVE'",
                Long.class,
                workspaceStockId
        );
    }

    private void insertInitialOpinion(long researchCycleId, long userId) {
        jdbcTemplate.update(
                """
                        INSERT INTO stock_opinion (
                            research_cycle_id, author_user_id, type, stance,
                            reason, risk_note, horizon, target_price, stop_loss_price
                        ) VALUES (?, ?, 'INITIAL', 'BULLISH', '迁移约束验证', '测试风险', 'MEDIUM', 12.0000, 9.0000)
                        """,
                researchCycleId,
                userId
        );
    }

    private record Fixture(long userId, long workspaceId, long stockId, long workspaceStockId) {
    }
}
