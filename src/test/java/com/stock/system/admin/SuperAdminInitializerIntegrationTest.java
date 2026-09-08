package com.stock.system.admin;

import com.stock.system.admin.entity.SuperAdmin;
import com.stock.system.admin.mapper.SuperAdminMapper;
import com.stock.system.admin.service.SuperAdminInitializerService;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@Rollback
class SuperAdminInitializerIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private SuperAdminInitializerService initializerService;

    @Autowired
    private SuperAdminMapper superAdminMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldCreateUserAndSingletonAdminInOneTransaction() {
        Long userId = initializerService.initialize("Root_Admin", "AdminPass123", "系统管理员");

        AppUser user = userService.findById(userId);
        SuperAdmin superAdmin = superAdminMapper.selectById(1);

        assertThat(user.getUsername()).isEqualTo("root_admin");
        assertThat(passwordEncoder.matches("AdminPass123", user.getPasswordHash())).isTrue();
        assertThat(superAdmin.getUserId()).isEqualTo(userId);
    }
}
