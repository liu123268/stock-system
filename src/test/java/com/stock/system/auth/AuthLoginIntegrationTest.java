package com.stock.system.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.auth.vo.RegisterResponseVO;
import com.stock.system.security.JwtUtil;
import com.stock.system.support.MySqlIntegrationTest;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class AuthLoginIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void shouldLoginAndIssueValidJwt() throws Exception {
        RegisterResponseVO registeredUser = authService.register(
                new RegisterRequestDTO("login_user", "LoginPass123", "登录测试用户")
        );

        String requestBody = """
                {
                  "username": "login_user",
                  "password": "LoginPass123"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(86400))
                .andExpect(jsonPath("$.data.username").value("login_user"))
                .andReturn();

        JsonNode responseJson = objectMapper.readTree(result.getResponse().getContentAsString());
        String accessToken = responseJson.path("data").path("accessToken").asText();
        Claims claims = jwtUtil.parseToken(accessToken);

        assertThat(accessToken).isNotBlank();
        assertThat(claims.get("userId", Long.class)).isEqualTo(registeredUser.userId());
        assertThat(claims.get("username", String.class)).isEqualTo("login_user");
    }

    @Test
    void shouldRejectWrongPasswordWithoutRevealingDetails() throws Exception {
        authService.register(new RegisterRequestDTO("wrong_password_user", "CorrectPass123", "错误密码测试"));

        String requestBody = """
                {
                  "username": "wrong_password_user",
                  "password": "WrongPass123"
                }
                """;

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("登录账号或密码错误"));
    }

    @Test
    void shouldRejectPasswordLongerThanSixtyFourCharacters() throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "username", "length_probe_user",
                "password", "a".repeat(65)
        ));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("密码长度必须在4到64个字符之间"));
    }
}
