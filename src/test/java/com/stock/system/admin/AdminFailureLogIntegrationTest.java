package com.stock.system.admin;

import com.stock.system.admin.service.AdminGovernanceService;
import com.stock.system.admin.service.SuperAdminInitializerService;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.security.LoginUser;
import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AdminFailureLogIntegrationTest extends MySqlIntegrationTest {

    private final String username = ("fail_admin_" + UUID.randomUUID().toString().replace("-", ""))
            .substring(0, 20);
    private Long adminUserId;

    @Autowired private SuperAdminInitializerService initializerService;
    @Autowired private AdminGovernanceService governanceService;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanupCommittedFailureLogAndAdministrator() {
        SecurityContextHolder.clearContext();
        Long userId = adminUserId;
        if (userId == null) {
            userId = jdbc.query(
                    "SELECT id FROM app_user WHERE username=?",
                    resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
                    username
            );
        }
        if (userId != null) {
            jdbc.update("DELETE FROM super_admin_operation_log WHERE target_id=?", userId);
            jdbc.update("DELETE FROM super_admin WHERE user_id=?", userId);
            jdbc.update("DELETE FROM app_user WHERE id=? AND username=?", userId, username);
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username=?", Long.class, username)).isZero();
        if (userId != null) {
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM super_admin_operation_log WHERE target_id=?",
                    Long.class,
                    userId
            )).isZero();
        }
    }

    @Test
    void commitsExpectedBusinessFailureLogInAnIndependentTransaction() {
        adminUserId = initializerService.initialize(username, "AdminPass123", "失败日志管理员");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new LoginUser(adminUserId, username, "SUPER_ADMIN"),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
                )
        );

        assertThatThrownBy(() -> governanceService.disableUser(adminUserId, adminUserId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.SUPER_ADMIN_CANNOT_DISABLE_SELF);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM super_admin_operation_log
                WHERE action='DISABLE_USER' AND target_id=? AND result='FAILED'
                """, Long.class, adminUserId)).isEqualTo(1);
    }
}
