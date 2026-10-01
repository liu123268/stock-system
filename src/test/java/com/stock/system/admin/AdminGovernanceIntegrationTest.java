package com.stock.system.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.system.admin.mapper.SuperAdminOperationLogMapper;
import com.stock.system.admin.service.SuperAdminInitializerService;
import com.stock.system.admin.service.AdminGovernanceTransactionService;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.auth.vo.RegisterResponseVO;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.workspace.service.WorkspaceInvitationService;
import com.stock.system.workspace.service.WorkspaceService;
import com.stock.system.workspace.vo.CurrentInvitationResponseVO;
import com.stock.system.workspace.vo.WorkspaceDetailResponseVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class AdminGovernanceIntegrationTest extends MySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthService authService;
    @Autowired private SuperAdminInitializerService initializerService;
    @Autowired private AdminGovernanceTransactionService transactionService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceInvitationService invitationService;
    @Autowired private SuperAdminOperationLogMapper operationLogMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void governsUsersAndInvalidatesAlreadyIssuedToken() throws Exception {
        initializerService.initialize("governance_admin", "AdminPass123", "治理管理员");
        RegisterResponseVO regular = authService.register(
                new RegisterRequestDTO("governance_user", "UserPass123", "治理用户"));
        String adminToken = login("governance_admin", "AdminPass123");
        String regularToken = login("governance_user", "UserPass123");

        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(regularToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(operationLogMapper.selectCount(null)).isZero();

        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.username == 'governance_user')]").exists())
                .andExpect(jsonPath("$.data.items[0].passwordHash").doesNotExist());

        mockMvc.perform(post("/api/admin/users/{userId}/disable", regular.userId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(get("/api/stocks/search").param("keyword", "茅台")
                        .header("Authorization", bearer(regularToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        mockMvc.perform(post("/api/admin/users/{userId}/restore", regular.userId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(regularToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountType").value("REGULAR"));

        assertThat(operationLogMapper.selectCount(null)).isEqualTo(2);
    }

    @Test
    void governsWorkspaceWithoutExposingResearchContent() throws Exception {
        Long adminUserId = initializerService.initialize(
                "workspace_admin", "AdminPass123", "空间管理员");
        RegisterResponseVO owner = authService.register(
                new RegisterRequestDTO("workspace_owner", "OwnerPass123", "空间所有者"));
        WorkspaceDetailResponseVO workspace = workspaceService.create(owner.userId(), "治理测试空间");
        CurrentInvitationResponseVO invitation = invitationService.create(
                owner.userId(), workspace.workspaceId(), null);
        String adminToken = login("workspace_admin", "AdminPass123");

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(adminUserId))
                .andExpect(jsonPath("$.data.accountType").value("SUPER_ADMIN"));

        mockMvc.perform(get("/api/admin/workspaces/{workspaceId}", workspace.workspaceId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("治理测试空间"))
                .andExpect(jsonPath("$.data.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.data.stocks").doesNotExist())
                .andExpect(jsonPath("$.data.opinions").doesNotExist())
                .andExpect(jsonPath("$.data.inviteCode").doesNotExist());

        mockMvc.perform(post("/api/admin/workspaces/{workspaceId}/archive", workspace.workspaceId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED_BY_SUPER_ADMIN"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM workspace_invitation WHERE id=?",
                String.class,
                invitation.invitationId()
        )).isEqualTo("REVOKED");

        mockMvc.perform(post("/api/admin/workspaces/{workspaceId}/restore", workspace.workspaceId())
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(get("/api/admin/operation-logs")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].result").value("SUCCESS"));
    }

    @Test
    void searchesUnicodeNicknamesAndKeepsAccountMatchingCaseSensitive() throws Exception {
        initializerService.initialize("unicode_admin", "AdminPass123", "搜索管理员");
        authService.register(new RegisterRequestDTO(
                "unicode_owner", "OwnerPass123", "中文研究成员%😀"));
        String adminToken = login("unicode_admin", "AdminPass123");

        for (String keyword : java.util.List.of("中文", "unicode_owner", "%", "😀")) {
            mockMvc.perform(get("/api/admin/users").param("keyword", keyword)
                            .header("Authorization", bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.items[0].username").value("unicode_owner"));
        }
        for (String keyword : java.util.List.of("找不到的账号", "UNICODE_OWNER")) {
            mockMvc.perform(get("/api/admin/users").param("keyword", keyword)
                            .header("Authorization", bearer(adminToken)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.items").isEmpty());
        }
    }

    @Test
    void preventsTheOnlySuperAdminFromDisablingItself() {
        Long adminUserId = initializerService.initialize(
                "protected_admin", "AdminPass123", "受保护管理员");

        assertThatThrownBy(() -> transactionService.disableUser(adminUserId, adminUserId))
                .hasMessage("唯一超级管理员不能禁用自己的账号");
    }

    private String login(String username, String password) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "username", username,
                "password", password
        ));
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.path("data").path("accessToken").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
