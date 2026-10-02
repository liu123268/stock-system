package com.stock.system.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.mapper.AppUserMapper;
import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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

    @Autowired
    private ObjectMapper objectMapper;

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
    void shouldAcceptTwoCharacterUsernameAndSixCharacterPassword() throws Exception {
        String requestBody = """
                {
                  "username": "Ab",
                  "password": "a1B2c3",
                  "nickname": "林"
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value("ab"));
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 65})
    void shouldRejectNewPasswordsOutsideLengthRange(int length) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "length_check", "password", "a".repeat(length), "nickname", "长度测试"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertThat(appUserMapper.selectCount(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, "length_check"))).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "abcdefghijklmnopqrstu", "中文", "a-"})
    void shouldRejectInvalidUsernames(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", "ValidPass123", "nickname", "账号测试"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
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
