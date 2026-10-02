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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        Long userId = initializerService.initialize("Ad", "a1B2c3", "系统管理员");

        AppUser user = userService.findById(userId);
        SuperAdmin superAdmin = superAdminMapper.selectById(1);

        assertThat(user.getUsername()).isEqualTo("ad");
        assertThat(passwordEncoder.matches("a1B2c3", user.getPasswordHash())).isTrue();
        assertThat(superAdmin.getUserId()).isEqualTo(userId);
    }

    @Test
    void shouldRejectShortPasswordBeforeCreatingAdministrator() {
        for (String password : new String[] {"a1B2", "a1B2c"}) {
            assertThatThrownBy(() -> initializerService.initialize("ad", password, "管理员"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("超级管理员密码长度必须在6到64个字符之间");
        }
        assertThat(userService.findByUsername("ad")).isNull();
        assertThat(superAdminMapper.selectCount(null)).isZero();
    }
}
