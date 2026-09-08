package com.stock.system.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.mapper.AppUserMapper;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.entity.Workspace;
import com.stock.system.workspace.entity.WorkspaceInvitation;
import com.stock.system.workspace.entity.WorkspaceMember;
import com.stock.system.workspace.mapper.WorkspaceInvitationMapper;
import com.stock.system.workspace.mapper.WorkspaceMapper;
import com.stock.system.workspace.mapper.WorkspaceMemberMapper;
import com.stock.system.workspace.service.WorkspaceInvitationService;
import com.stock.system.workspace.service.WorkspaceMemberService;
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
class OwnershipTransferConcurrencyIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private WorkspaceInvitationService invitationService;

    @Autowired
    private WorkspaceMemberService memberService;

    @Autowired
    private WorkspaceMemberMapper memberMapper;

    @Autowired
    private WorkspaceInvitationMapper invitationMapper;

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
                        new LambdaQueryWrapper<Workspace>()
                                .eq(Workspace::getId, createdWorkspaceId)
                )).isZero();
    }

    @Test
    void shouldAllowOnlyOneConcurrentOwnershipTransfer() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36).substring(0, 7);
        Long ownerId = register("to_" + suffix);
        Long firstTargetId = register("ta_" + suffix);
        Long secondTargetId = register("tb_" + suffix);
        createdWorkspaceId = workspaceService.create(ownerId, "并发转让" + suffix).workspaceId();
        String inviteCode = invitationService.create(ownerId, createdWorkspaceId, null).inviteCode();
        invitationService.join(firstTargetId, inviteCode);
        invitationService.join(secondTargetId, inviteCode);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = transferAsync(
                    executor,
                    startTogether,
                    ownerId,
                    firstTargetId
            );
            Future<String> second = transferAsync(
                    executor,
                    startTogether,
                    ownerId,
                    secondTargetId
            );
            List<String> results = List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            );

            assertThat(results).filteredOn("SUCCESS"::equals).hasSize(1);
            assertThat(results).filteredOn("WORKSPACE_ACCESS_DENIED"::equals).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        Workspace workspace = workspaceMapper.selectById(createdWorkspaceId);
        assertThat(workspace.getOwnerUserId()).isIn(firstTargetId, secondTargetId);
        assertThat(memberMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceMember>()
                        .eq(WorkspaceMember::getWorkspaceId, createdWorkspaceId)
                        .eq(WorkspaceMember::getStatus, "ACTIVE")
        )).isEqualTo(3);
    }

    private Future<String> transferAsync(
            ExecutorService executor,
            CyclicBarrier startTogether,
            Long ownerId,
            Long targetId
    ) {
        return executor.submit(() -> {
            startTogether.await(10, TimeUnit.SECONDS);
            try {
                memberService.transfer(ownerId, createdWorkspaceId, targetId);
                return "SUCCESS";
            } catch (BusinessException exception) {
                return exception.getErrorCode().name();
            }
        });
    }

    private Long register(String username) {
        authService.register(new RegisterRequestDTO(username, "TestPass123", username));
        AppUser user = userService.findByUsername(username);
        createdUserIds.add(user.getId());
        return user.getId();
    }
}
