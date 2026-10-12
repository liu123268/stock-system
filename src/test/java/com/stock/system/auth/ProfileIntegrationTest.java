package com.stock.system.auth;

import tools.jackson.databind.ObjectMapper;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.service.UserService;
import com.stock.system.user.entity.AppUser;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class ProfileIntegrationTest extends MySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void shouldLetLegacyFourCharacterPasswordLoginAndUpgradeWithoutLosingAccount() throws Exception {
        // 直接构造历史账号，不能用新注册接口创建已禁止的四位密码。
        AppUser legacy = new AppUser();
        legacy.setUsername("legacy_password");
        legacy.setNickname("历史账号");
        legacy.setStatus("ACTIVE");
        legacy.setPasswordHash(passwordEncoder.encode("a1B2"));
        userService.create(legacy);
        String token = login("legacy_password", "a1B2");

        for (String invalidPassword : new String[] {"a1B2", "a1B2c", "a".repeat(65)}) {
            mockMvc.perform(post("/api/auth/password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(java.util.Map.of(
                                    "currentPassword", "a1B2", "newPassword", invalidPassword))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(userService.findById(legacy.getId()).getCredentialVersion()).isZero();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a1B2\",\"newPassword\":\"a1B2c3\"}"))
                .andExpect(status().isOk());
        AppUser updated = userService.findByUsername("legacy_password");
        assertThat(updated.getId()).isEqualTo(legacy.getId());
        assertThat(updated.getNickname()).isEqualTo("历史账号");
        assertThat(updated.getCredentialVersion()).isEqualTo(1);
        assertThat(passwordEncoder.matches("a1B2c3", updated.getPasswordHash())).isTrue();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"legacy_password\",\"password\":\"a1B2\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        String newToken = login("legacy_password", "a1B2c3");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldUpdateOnlyOwnNicknameWithoutInvalidatingToken() throws Exception {
        authService.register(new RegisterRequestDTO("profile_owner", "OldPass123", "原昵称"));
        String token = login("profile_owner", "OldPass123");

        mockMvc.perform(patch("/api/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\" 新昵称 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("新昵称"));
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("新昵称"));
        assertThat(userService.findByUsername("profile_owner").getNickname()).isEqualTo("新昵称");

        mockMvc.perform(patch("/api/auth/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"非法修改\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/auth/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectWrongOldPasswordAndInvalidateEveryOldTokenAfterChange() throws Exception {
        authService.register(new RegisterRequestDTO("profile_password", "OldPass123", "改密用户"));
        String firstToken = login("profile_password", "OldPass123");
        String secondToken = login("profile_password", "OldPass123");

        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"NewPass456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/password")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"OldPass123\",\"newPassword\":\"NewPass456\"}"))
                .andExpect(status().isOk());
        assertThat(userService.findByUsername("profile_password").getCredentialVersion()).isEqualTo(1);

        for (String oldToken : new String[] {firstToken, secondToken}) {
            mockMvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + oldToken))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"profile_password\",\"password\":\"OldPass123\"}"))
                .andExpect(status().isUnauthorized());
        String newToken = login("profile_password", "NewPass456");
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    private String login(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(new com.stock.system.auth.dto.LoginRequestDTO(username, password));
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }
}
