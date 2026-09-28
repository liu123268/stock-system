package com.stock.system.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
