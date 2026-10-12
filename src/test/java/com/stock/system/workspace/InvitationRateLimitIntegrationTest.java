package com.stock.system.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.mapper.AppUserMapper;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.entity.WorkspaceInvitation;
import com.stock.system.workspace.entity.WorkspaceMember;
import com.stock.system.workspace.mapper.WorkspaceInvitationMapper;
import com.stock.system.workspace.mapper.WorkspaceMapper;
import com.stock.system.workspace.mapper.WorkspaceMemberMapper;
import com.stock.system.workspace.service.WorkspaceInvitationService;
import com.stock.system.workspace.service.WorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InvitationRateLimitIntegrationTest extends MySqlIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceInvitationService invitationService;
    @Autowired private WorkspaceInvitationMapper invitationMapper;
    @Autowired private WorkspaceMemberMapper memberMapper;
    @Autowired private WorkspaceMapper workspaceMapper;
    @Autowired private AppUserMapper userMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long createdWorkspaceId;

    @AfterEach
    void cleanCreatedData() {
        if (createdWorkspaceId != null) {
            invitationMapper.delete(new LambdaQueryWrapper<WorkspaceInvitation>()
                    .eq(WorkspaceInvitation::getWorkspaceId, createdWorkspaceId));
            memberMapper.delete(new LambdaQueryWrapper<WorkspaceMember>()
                    .eq(WorkspaceMember::getWorkspaceId, createdWorkspaceId));
            workspaceMapper.deleteById(createdWorkspaceId);
        }
        if (!createdUserIds.isEmpty()) {
            userMapper.deleteBatchIds(createdUserIds);
        }
        for (Long userId : createdUserIds) {
            assertThat(userMapper.selectById(userId)).isNull();
            assertThat(failureRows(userId)).isZero();
        }
        if (createdWorkspaceId != null) {
            assertThat(workspaceMapper.selectById(createdWorkspaceId)).isNull();
        }
    }

    @Test
    void shouldPersistFailuresLimitSixthAttemptAndClearAfterSuccess() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).substring(0, 7);
        Long ownerId = register("rl_o_" + suffix);
        Long memberId = register("rl_m_" + suffix);
        Long otherId = register("rl_x_" + suffix);
        createdWorkspaceId = workspaceService.create(ownerId, "限流持久化" + suffix).workspaceId();
        String inviteCode = invitationService.create(ownerId, createdWorkspaceId, null).inviteCode();
        String wrongCode = inviteCode.equals("000000") ? "111111" : "000000";
        String memberToken = login("rl_m_" + suffix);
        String otherToken = login("rl_x_" + suffix);

        for (int attempt = 0; attempt < 5; attempt++) {
            join(memberToken, wrongCode, 404, "INVITATION_NOT_FOUND");
        }
        assertThat(failureCount(memberId)).isEqualTo(5);
        join(memberToken, inviteCode, 429, "INVITATION_ATTEMPTS_LIMITED");
        assertThat(invitationMapper.selectOne(new LambdaQueryWrapper<WorkspaceInvitation>()
                .eq(WorkspaceInvitation::getInviteCode, inviteCode)).getUsedCount()).isZero();

        join(otherToken, inviteCode, 200, "SUCCESS");
        assertThat(failureRows(otherId)).isZero();
        jdbcTemplate.update("UPDATE invitation_join_attempt SET window_started_at = ? WHERE user_id = ?",
                LocalDateTime.now().minusMinutes(16), memberId);
        join(memberToken, inviteCode, 200, "SUCCESS");
        assertThat(failureRows(memberId)).isZero();
        join(memberToken, wrongCode, 404, "INVITATION_NOT_FOUND");
        assertThat(failureCount(memberId)).isEqualTo(1);
        join(memberToken, inviteCode, 200, "SUCCESS");
        assertThat(failureRows(memberId)).isZero();
    }

    @Test
    void shouldSerializeConcurrentGuessesFromOneAccount() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).substring(0, 7);
        Long memberId = register("rl_c_" + suffix);
        CyclicBarrier barrier = new CyclicBarrier(6);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int attempt = 0; attempt < 6; attempt++) {
                results.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        invitationService.join(memberId, "000000");
                        return "SUCCESS";
                    } catch (BusinessException exception) {
                        return exception.getErrorCode().name();
                    }
                }));
            }
            List<String> codes = new ArrayList<>();
            for (Future<String> result : results) {
                codes.add(result.get(20, TimeUnit.SECONDS));
            }
            assertThat(codes).filteredOn("INVITATION_NOT_FOUND"::equals).hasSize(5);
            assertThat(codes).filteredOn("INVITATION_ATTEMPTS_LIMITED"::equals).hasSize(1);
            assertThat(failureCount(memberId)).isEqualTo(5);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Long register(String username) {
        authService.register(new RegisterRequestDTO(username, "TestPass123", username));
        AppUser user = userService.findByUsername(username);
        createdUserIds.add(user.getId());
        return user.getId();
    }

    private String login(String username) {
        return authService.login(new LoginRequestDTO(username, "TestPass123")).accessToken();
    }

    private void join(String token, String code, int httpStatus, String errorCode) throws Exception {
        mockMvc.perform(post("/api/workspaces/join")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + code + "\"}"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(errorCode));
    }

    private int failureRows(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invitation_join_attempt WHERE user_id = ?", Integer.class, userId);
    }

    private int failureCount(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT failure_count FROM invitation_join_attempt WHERE user_id = ?", Integer.class, userId);
    }
}
