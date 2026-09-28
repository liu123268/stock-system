package com.stock.system.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.entity.WorkspaceInvitation;
import com.stock.system.workspace.entity.Workspace;
import com.stock.system.workspace.entity.WorkspaceMember;
import com.stock.system.workspace.mapper.WorkspaceInvitationMapper;
import com.stock.system.workspace.mapper.WorkspaceMapper;
import com.stock.system.workspace.mapper.WorkspaceMemberMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class WorkspaceIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkspaceInvitationMapper invitationMapper;

    @Autowired
    private WorkspaceMapper workspaceMapper;

    @Autowired
    private WorkspaceMemberMapper memberMapper;

    @Autowired
    private UserService userService;

    @Test
    void shouldCreateListAndReadWorkspaceWithOwnerMembership() throws Exception {
        String token = registerAndLogin("workspace_owner");

        String response = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" 家 庭　研究组 \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value("家庭研究组"))
                .andExpect(jsonPath("$.data.role").value("OWNER"))
                .andExpect(jsonPath("$.data.memberCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        long workspaceId = json.path("data").path("workspaceId").asLong();

        mockMvc.perform(get("/api/workspaces")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].workspaceId").value(workspaceId));

        mockMvc.perform(get("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("家庭研究组"));
    }

    @Test
    void shouldRejectDuplicateNameAndHideWorkspaceFromNonMember() throws Exception {
        String ownerToken = registerAndLogin("workspace_owner_two");
        String strangerToken = registerAndLogin("workspace_stranger");

        String response = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"唯一空间\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long workspaceId = objectMapper.readTree(response).path("data").path("workspaceId").asLong();

        mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"唯一空间\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NAME_ALREADY_EXISTS"));

        mockMvc.perform(get("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));
    }

    @Test
    void shouldRenameOnlyActiveOwnedWorkspaceAndKeepNameUnique() throws Exception {
        String ownerToken = registerAndLogin("rename_owner");
        String memberToken = registerAndLogin("rename_member");
        String strangerToken = registerAndLogin("rename_stranger");
        long workspaceId = createWorkspace(ownerToken, "改名前空间");
        createWorkspace(strangerToken, "已有空间");
        JsonNode invitation = createInvitation(ownerToken, workspaceId, null);
        joinWorkspace(memberToken, invitation.path("inviteCode").asText());

        mockMvc.perform(patch("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"成员不能改\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"陌生人不能改\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"已 有 空 间\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NAME_ALREADY_EXISTS"));
        mockMvc.perform(patch("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"　\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新 名　称\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("新名称"));
        assertThat(workspaceMapper.selectById(workspaceId).getName()).isEqualTo("新名称");

        mockMvc.perform(post("/api/workspaces/{workspaceId}/archive", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"归档后名称\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ARCHIVED"));
        assertThat(workspaceMapper.selectById(workspaceId).getName()).isEqualTo("新名称");
    }

    @Test
    void shouldGenerateJoinAndHideInvitationCodeFromHistoryAndMembers() throws Exception {
        String ownerToken = registerAndLogin("invitation_owner");
        String memberToken = registerAndLogin("invitation_member");
        long workspaceId = createWorkspace(ownerToken, "邀请测试空间");

        String invitationResponse = mockMvc.perform(
                        post("/api/workspaces/{workspaceId}/invitations", workspaceId)
                                .header("Authorization", "Bearer " + ownerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"currentInvitationId\":null}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.inviteCode").isString())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.remainingUses").value(5))
                .andReturn().getResponse().getContentAsString();
        JsonNode invitation = objectMapper.readTree(invitationResponse).path("data");
        String inviteCode = invitation.path("inviteCode").asText();
        long invitationId = invitation.path("invitationId").asLong();
        assertThat(inviteCode).matches("[A-Z0-9]{6}");

        mockMvc.perform(get("/api/workspaces/{workspaceId}/invitations", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].invitationId").value(invitationId))
                .andExpect(jsonPath("$.data[0].inviteCode").doesNotExist());

        mockMvc.perform(post("/api/workspaces/join")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newlyJoined").value(true))
                .andExpect(jsonPath("$.data.workspace.workspaceId").value(workspaceId))
                .andExpect(jsonPath("$.data.workspace.role").value("MEMBER"))
                .andExpect(jsonPath("$.data.workspace.memberCount").value(2));

        mockMvc.perform(post("/api/workspaces/join")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newlyJoined").value(false));

        assertThat(invitationMapper.selectById(invitationId).getUsedCount()).isEqualTo(1);
        mockMvc.perform(get("/api/workspaces/{workspaceId}/invitations/current", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void shouldRevokeOldInvitationAndRejectStaleOwnerAction() throws Exception {
        String ownerToken = registerAndLogin("replace_invite_owner");
        String memberToken = registerAndLogin("replace_invite_mem");
        long workspaceId = createWorkspace(ownerToken, "换码测试空间");
        JsonNode first = createInvitation(ownerToken, workspaceId, null);
        JsonNode second = createInvitation(ownerToken, workspaceId, first.path("invitationId").asLong());

        mockMvc.perform(post("/api/workspaces/join")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + first.path("inviteCode").asText() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_REVOKED"));

        mockMvc.perform(delete("/api/workspaces/{workspaceId}/invitations/{invitationId}",
                        workspaceId, first.path("invitationId").asLong())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_CHANGED"));

        mockMvc.perform(delete("/api/workspaces/{workspaceId}/invitations/{invitationId}",
                        workspaceId, second.path("invitationId").asLong())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectExpiredExhaustedAndPreRemovalInvitation() throws Exception {
        String ownerToken = registerAndLogin("invalid_invite_owner");
        String memberToken = registerAndLogin("invalid_invite_mem");
        long workspaceId = createWorkspace(ownerToken, "失效码测试空间");

        JsonNode expiredJson = createInvitation(ownerToken, workspaceId, null);
        WorkspaceInvitation expired = invitationMapper.selectById(expiredJson.path("invitationId").asLong());
        expired.setCreatedAt(java.time.LocalDateTime.now().minusDays(2));
        expired.setExpiresAt(java.time.LocalDateTime.now().minusDays(1));
        invitationMapper.updateById(expired);
        expectJoinError(memberToken, expired.getInviteCode(), 409, "INVITATION_EXPIRED");

        JsonNode exhaustedJson = createInvitation(ownerToken, workspaceId, expired.getId());
        WorkspaceInvitation exhausted = invitationMapper.selectById(
                exhaustedJson.path("invitationId").asLong()
        );
        exhausted.setUsedCount(5);
        invitationMapper.updateById(exhausted);
        expectJoinError(memberToken, exhausted.getInviteCode(), 409, "INVITATION_EXHAUSTED");

        JsonNode oldJson = createInvitation(ownerToken, workspaceId, exhausted.getId());
        String oldCode = oldJson.path("inviteCode").asText();
        mockMvc.perform(post("/api/workspaces/join")
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + oldCode + "\"}"))
                .andExpect(status().isOk());

        Long memberUserId = userService.findByUsername("invalid_invite_mem").getId();
        WorkspaceMember member = memberMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, memberUserId)
        );
        member.setStatus("REMOVED");
        member.setLastInactiveAt(java.time.LocalDateTime.now().plusSeconds(1));
        memberMapper.updateById(member);
        expectJoinError(memberToken, oldCode, 403, "REMOVED_MEMBER_REQUIRES_NEW_INVITATION");
    }

    @Test
    void shouldListMembersLeaveAndRejoinWithTheSameMembership() throws Exception {
        String ownerToken = registerAndLogin("member_list_owner");
        String memberToken = registerAndLogin("member_list_user");
        long workspaceId = createWorkspace(ownerToken, "成员退出测试空间");
        JsonNode invitation = createInvitation(ownerToken, workspaceId, null);
        String inviteCode = invitation.path("inviteCode").asText();
        joinWorkspace(memberToken, inviteCode);

        Long memberUserId = userService.findByUsername("member_list_user").getId();
        mockMvc.perform(get("/api/workspaces/{workspaceId}/members", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].role").value("OWNER"))
                .andExpect(jsonPath("$.data[1].userId").value(memberUserId))
                .andExpect(jsonPath("$.data[1].role").value("MEMBER"));

        WorkspaceMember before = findMember(workspaceId, memberUserId);
        mockMvc.perform(post("/api/workspaces/{workspaceId}/leave", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBERSHIP_INACTIVE"));

        joinWorkspace(memberToken, inviteCode);
        WorkspaceMember after = findMember(workspaceId, memberUserId);
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getFirstJoinedAt()).isEqualTo(before.getFirstJoinedAt());
        assertThat(after.getLastJoinedAt()).isAfterOrEqualTo(before.getLastJoinedAt());
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
        assertThat(invitationMapper.selectById(invitation.path("invitationId").asLong()).getUsedCount())
                .isEqualTo(2);
    }

    @Test
    void shouldRemoveMemberRejectOldInvitationAndAllowANewInvitation() throws Exception {
        String ownerToken = registerAndLogin("remove_owner");
        String memberToken = registerAndLogin("remove_member");
        long workspaceId = createWorkspace(ownerToken, "成员移除测试空间");
        JsonNode oldInvitation = createInvitation(ownerToken, workspaceId, null);
        joinWorkspace(memberToken, oldInvitation.path("inviteCode").asText());
        Long memberUserId = userService.findByUsername("remove_member").getId();
        Long membershipId = findMember(workspaceId, memberUserId).getId();

        mockMvc.perform(delete("/api/workspaces/{workspaceId}/members/{userId}",
                        workspaceId, memberUserId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));

        mockMvc.perform(delete("/api/workspaces/{workspaceId}/members/{userId}",
                        workspaceId, memberUserId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/workspaces/{workspaceId}/members/{userId}",
                        workspaceId, memberUserId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        WorkspaceMember removed = findMember(workspaceId, memberUserId);
        WorkspaceInvitation oldInvitationAtRemovalBoundary = invitationMapper.selectById(
                oldInvitation.path("invitationId").asLong()
        );
        oldInvitationAtRemovalBoundary.setCreatedAt(removed.getLastInactiveAt());
        invitationMapper.updateById(oldInvitationAtRemovalBoundary);

        expectJoinError(
                memberToken,
                oldInvitation.path("inviteCode").asText(),
                403,
                "REMOVED_MEMBER_REQUIRES_NEW_INVITATION"
        );

        JsonNode newInvitation = createInvitation(
                ownerToken,
                workspaceId,
                oldInvitation.path("invitationId").asLong()
        );
        assertThat(invitationMapper.selectById(newInvitation.path("invitationId").asLong())
                .getCreatedAt()).isAfter(removed.getLastInactiveAt());
        joinWorkspace(memberToken, newInvitation.path("inviteCode").asText());
        WorkspaceMember restored = findMember(workspaceId, memberUserId);
        assertThat(restored.getId()).isEqualTo(membershipId);
        assertThat(restored.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldTransferOwnershipAndPreventOwnerFromLeavingDirectly() throws Exception {
        String ownerToken = registerAndLogin("transfer_owner");
        String memberToken = registerAndLogin("transfer_member");
        String strangerToken = registerAndLogin("transfer_stranger");
        long workspaceId = createWorkspace(ownerToken, "所有权转让测试空间");
        JsonNode invitation = createInvitation(ownerToken, workspaceId, null);
        joinWorkspace(memberToken, invitation.path("inviteCode").asText());
        Long memberUserId = userService.findByUsername("transfer_member").getId();
        Long strangerUserId = userService.findByUsername("transfer_stranger").getId();

        mockMvc.perform(post("/api/workspaces/{workspaceId}/leave", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_CANNOT_LEAVE"));

        mockMvc.perform(post("/api/workspaces/{workspaceId}/ownership-transfer", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + strangerUserId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_TARGET_NOT_ACTIVE_MEMBER"));

        mockMvc.perform(post("/api/workspaces/{workspaceId}/ownership-transfer", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + memberUserId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerUserId").value(memberUserId))
                .andExpect(jsonPath("$.data.role").value("MEMBER"));

        mockMvc.perform(get("/api/workspaces/{workspaceId}/members", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(memberUserId))
                .andExpect(jsonPath("$.data[0].role").value("OWNER"));

        mockMvc.perform(delete("/api/workspaces/{workspaceId}/members/{userId}",
                        workspaceId, memberUserId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    @Test
    void shouldArchiveAsOwnerEnforceReadOnlyAndRestoreByArchiveSource() throws Exception {
        String ownerToken = registerAndLogin("archive_owner");
        String memberToken = registerAndLogin("archive_member");
        long workspaceId = createWorkspace(ownerToken, "归档测试空间");
        JsonNode invitation = createInvitation(ownerToken, workspaceId, null);
        joinWorkspace(memberToken, invitation.path("inviteCode").asText());
        Long memberUserId = userService.findByUsername("archive_member").getId();

        mockMvc.perform(post("/api/workspaces/{workspaceId}/archive", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));

        mockMvc.perform(post("/api/workspaces/{workspaceId}/archive", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED_BY_OWNER"))
                .andExpect(jsonPath("$.data.archivedAt").isString());
        mockMvc.perform(post("/api/workspaces/{workspaceId}/archive", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED_BY_OWNER"));

        assertThat(invitationMapper.selectById(invitation.path("invitationId").asLong()).getStatus())
                .isEqualTo("REVOKED");
        mockMvc.perform(get("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ARCHIVED_BY_OWNER"));
        mockMvc.perform(get("/api/workspaces/{workspaceId}/members", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(post("/api/workspaces/{workspaceId}/invitations", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentInvitationId\":null}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ARCHIVED"));
        mockMvc.perform(delete("/api/workspaces/{workspaceId}/members/{userId}",
                        workspaceId, memberUserId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ARCHIVED"));

        mockMvc.perform(post("/api/workspaces/{workspaceId}/leave", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/workspaces/{workspaceId}", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBERSHIP_INACTIVE"));

        mockMvc.perform(post("/api/workspaces/{workspaceId}/restore", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.archivedAt").isEmpty());
        mockMvc.perform(post("/api/workspaces/{workspaceId}/restore", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        Workspace adminArchived = workspaceMapper.selectById(workspaceId);
        adminArchived.setStatus("ARCHIVED_BY_SUPER_ADMIN");
        adminArchived.setArchivedAt(LocalDateTime.now());
        workspaceMapper.updateById(adminArchived);
        mockMvc.perform(post("/api/workspaces/{workspaceId}/restore", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ACCESS_DENIED"));
    }

    private long createWorkspace(String token, String name) throws Exception {
        String response = mockMvc.perform(post("/api/workspaces")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("workspaceId").asLong();
    }

    private JsonNode createInvitation(String token, long workspaceId, Long currentId)
            throws Exception {
        String currentValue = currentId == null ? "null" : currentId.toString();
        String response = mockMvc.perform(post("/api/workspaces/{workspaceId}/invitations", workspaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentInvitationId\":" + currentValue + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private void joinWorkspace(String token, String inviteCode) throws Exception {
        mockMvc.perform(post("/api/workspaces/join")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newlyJoined").value(true));
    }

    private WorkspaceMember findMember(long workspaceId, long userId) {
        return memberMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                        .eq(WorkspaceMember::getUserId, userId)
        );
    }

    private void expectJoinError(
            String token,
            String inviteCode,
            int expectedStatus,
            String errorCode
    )
            throws Exception {
        mockMvc.perform(post("/api/workspaces/join")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(errorCode));
    }

    private String registerAndLogin(String username) {
        authService.register(new RegisterRequestDTO(username, "TestPass123", username));
        return authService.login(new LoginRequestDTO(username, "TestPass123")).accessToken();
    }
}
