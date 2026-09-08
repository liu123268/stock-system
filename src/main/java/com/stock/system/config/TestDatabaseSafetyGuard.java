package com.stock.system.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * test profile 的最后一道数据库边界：检查 JDBC 实际连接，而不是只相信配置名称。
 */
@Component
@Profile("test")
public class TestDatabaseSafetyGuard implements FlywayMigrationStrategy {

    public static final String EXPECTED_DATABASE = "stock_system_test";

    private final DataSource dataSource;

    public TestDatabaseSafetyGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void migrate(Flyway flyway) {
        try {
            verifyDatabase();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法核对 test profile 的真实数据库", exception);
        }
        flyway.migrate();
    }

    void verifyDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            String actualDatabase = connection.getCatalog();
            if (!EXPECTED_DATABASE.equals(actualDatabase)) {
                throw new IllegalStateException(
                        "test profile 只能连接 " + EXPECTED_DATABASE + "，实际连接：" + actualDatabase);
            }
        }
    }
}
