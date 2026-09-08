package com.stock.system.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDatabaseSafetyGuardTest {

    @Test
    void shouldRejectDevelopmentDatabaseEvenWhenTestProfileWasSelected() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("stock_system");

        TestDatabaseSafetyGuard guard = new TestDatabaseSafetyGuard(dataSource);
        Flyway flyway = mock(Flyway.class);

        assertThatThrownBy(() -> guard.migrate(flyway))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stock_system_test")
                .hasMessageContaining("stock_system");
        verify(flyway, never()).migrate();
    }
}
