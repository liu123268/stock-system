package com.stock.system.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.mapper.AppUserMapper;
import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class AuthRegistrationIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserMapper appUserMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldRegisterUserAndStoreBCryptPasswordHash() throws Exception {
        String requestBody = """
                {
                  "username": "test_user",
                  "password": "TestPass123",
                  "nickname": "测试用户"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.username").value("test_user"))
                .andExpect(jsonPath("$.data.nickname").value("测试用户"));

        AppUser savedUser = appUserMapper.selectOne(
                new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, "test_user")
        );

        assertThat(savedUser).isNotNull();
        assertThat(savedUser.getPasswordHash()).isNotEqualTo("TestPass123");
        assertThat(passwordEncoder.matches("TestPass123", savedUser.getPasswordHash())).isTrue();
        assertThat(savedUser.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldRejectInvalidRegisterRequest() throws Exception {
        String requestBody = """
                {
                  "username": "a",
                  "password": "123",
                  "nickname": ""
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldAcceptFourCharacterPasswordForCurrentLocalStage() throws Exception {
        String requestBody = """
                {
                  "username": "short_password_user",
                  "password": "a1B2",
                  "nickname": "四位密码测试"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value("short_password_user"));
    }

    @Test
    void shouldNormalizeUsernameAndRejectDuplicateAccount() throws Exception {
        String firstRequest = """
                {
                  "username": "Mixed_User",
                  "password": "TestPass123",
                  "nickname": "第一个用户"
                }
                """;
        String duplicateRequest = """
                {
                  "username": "mixed_user",
                  "password": "TestPass456",
                  "nickname": "重复用户"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstRequest))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("mixed_user"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateRequest))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("登录账号已存在"));
    }
}
