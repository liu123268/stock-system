package com.stock.system.config;

import com.stock.system.security.JwtAuthenticationFilter;
import com.stock.system.security.JwtUtil;
import com.stock.system.security.RestAuthenticationEntryPoint;
import com.stock.system.admin.service.SuperAdminService;
import com.stock.system.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TestEnvironmentController.class)
@ActiveProfiles("test")
@ContextConfiguration(classes = TestEnvironmentController.class)
@Import({SecurityConfig.class, TestEnvironmentSecurityConfig.class,
        JwtAuthenticationFilter.class, RestAuthenticationEntryPoint.class})
class TestEnvironmentSecurityConfigWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DataSource dataSource;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserService userService;

    @MockBean
    private SuperAdminService superAdminService;

    @Test
    void testProfileAllowsAnonymousEnvironmentCheckWithoutOpeningARealDatabase() throws Exception {
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn("stock_system_test");

        mockMvc.perform(get("/api/test/environment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile").value("test"))
                .andExpect(jsonPath("$.data.database").value("stock_system_test"));
    }
}
