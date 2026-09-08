package com.stock.system.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InvitationConcurrencyIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private WorkspaceInvitationService invitationService;

    @Autowired
    private WorkspaceInvitationMapper invitationMapper;

    @Autowired
    private WorkspaceMemberMapper memberMapper;

    @Autowired
    private WorkspaceMapper workspaceMapper;

    @Autowired
    private AppUserMapper userMapper;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long createdWorkspaceId;

    @AfterEach
    void cleanCreatedData() {
        if (createdWorkspaceId != null) {
            invitationMapper.delete(
                    new LambdaQueryWrapper<WorkspaceInvitation>()
                            .eq(WorkspaceInvitation::getWorkspaceId, createdWorkspaceId)
            );
            memberMapper.delete(
                    new LambdaQueryWrapper<WorkspaceMember>()
                            .eq(WorkspaceMember::getWorkspaceId, createdWorkspaceId)
            );
            workspaceMapper.deleteById(createdWorkspaceId);
        }
        if (!createdUserIds.isEmpty()) {
            userMapper.deleteBatchIds(createdUserIds);
            assertThat(userMapper.selectBatchIds(createdUserIds)).isEmpty();
        }

        assertThat(createdWorkspaceId == null
                ? 0
                : workspaceMapper.selectCount(
                        new LambdaQueryWrapper<com.stock.system.workspace.entity.Workspace>()
                                .eq(com.stock.system.workspace.entity.Workspace::getId, createdWorkspaceId)
                )).isZero();
    }

    @Test
    void shouldAllowOnlyFiveOfSixConcurrentNewMembers() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).substring(0, 7);
        Long ownerId = register("co_" + suffix);
        createdWorkspaceId = workspaceService.create(ownerId, "并发邀请" + suffix).workspaceId();
        String inviteCode = invitationService.create(ownerId, createdWorkspaceId, null).inviteCode();

        List<Long> memberIds = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            memberIds.add(register("cm" + index + "_" + suffix));
        }

        CyclicBarrier startTogether = new CyclicBarrier(memberIds.size());
        ExecutorService executor = Executors.newFixedThreadPool(memberIds.size());
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (Long memberId : memberIds) {
                futures.add(executor.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    try {
                        invitationService.join(memberId, inviteCode);
                        return "SUCCESS";
                    } catch (BusinessException exception) {
                        return exception.getErrorCode().name();
                    }
                }));
            }

            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            assertThat(results).filteredOn("SUCCESS"::equals).hasSize(5);
            assertThat(results).filteredOn("INVITATION_EXHAUSTED"::equals).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        WorkspaceInvitation invitation = invitationMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceInvitation>()
                        .eq(WorkspaceInvitation::getInviteCode, inviteCode)
        );
        assertThat(invitation.getUsedCount()).isEqualTo(5);
        assertThat(memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, createdWorkspaceId)
                        .eq(WorkspaceMember::getStatus, "ACTIVE")
        )).isEqualTo(6);
    }

    private Long register(String username) {
        authService.register(new RegisterRequestDTO(username, "TestPass123", username));
        AppUser user = userService.findByUsername(username);
        createdUserIds.add(user.getId());
        return user.getId();
    }
}
