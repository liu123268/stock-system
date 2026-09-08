package com.stock.system.support;

import com.stock.system.config.TestDatabaseSafetyGuard;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 所有会写数据库的集成测试在开始前都要确认连接的是隔离测试库。
 */
@ActiveProfiles("test")
public abstract class MySqlIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void verifyTestDatabase() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog())
                    .as("数据库写入测试只能连接 %s", TestDatabaseSafetyGuard.EXPECTED_DATABASE)
                    .isEqualTo(TestDatabaseSafetyGuard.EXPECTED_DATABASE);
        }
    }
}
