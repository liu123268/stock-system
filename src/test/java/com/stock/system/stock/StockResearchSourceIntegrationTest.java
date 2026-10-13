package com.stock.system.stock;

import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.entity.StockOpinion;
import com.stock.system.stock.mapper.StockOpinionMapper;
import com.stock.system.stock.service.StockResearchLifecycleService;
import com.stock.system.stock.service.StockResearchService;
import com.stock.system.stock.service.StockResearchSourceService;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.entity.WorkspaceMember;
import com.stock.system.workspace.mapper.WorkspaceMemberMapper;
import com.stock.system.workspace.service.WorkspaceMemberService;
import com.stock.system.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional(isolation = Isolation.REPEATABLE_READ)
class StockResearchSourceIntegrationTest extends MySqlIntegrationTest {

    private static final String BASE = "/api/workspaces/{workspaceId}/stocks/{stockId}"
            + "/research/cycles/{cycleId}";

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthService authService;
    @Autowired private UserService userService;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private WorkspaceMemberMapper memberMapper;
    @Autowired private WorkspaceMemberService memberService;
    @Autowired private StockResearchService researchService;
    @Autowired private StockResearchLifecycleService lifecycleService;
    @Autowired private StockOpinionMapper opinionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StockResearchSourceService sourceService;

    private String ownerToken;
    private String memberToken;
    private String strangerToken;
    private long ownerId;
    private long memberId;
    private long workspaceId;
    private long stockId;
    private long cycleId;
    private long initialId;
    private LocalDateTime baseTime;

    @BeforeEach
    void createRollbackOnlyResearch() {
        ownerToken = register("source_owner");
        memberToken = register("source_member");
        strangerToken = register("source_stranger");
        ownerId = userService.findByUsername("source_owner").getId();
        memberId = userService.findByUsername("source_member").getId();
        workspaceId = workspaceService.create(ownerId, "周期资料测试空间").workspaceId();
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(memberId);
        member.setStatus("ACTIVE");
        memberMapper.insert(member);
        var added = researchService.add(ownerId, workspaceId, stockRequest("00700.HK"));
        stockId = added.workspaceStockId();
        cycleId = added.researchCycleId();
        initialId = jdbcTemplate.queryForObject(
                "select id from stock_opinion where research_cycle_id = ?", Long.class, cycleId);
        baseTime = LocalDateTime.now().minusMinutes(2).withNano(0);
        jdbcTemplate.update("update research_cycle set started_at = ? where id = ?", baseTime, cycleId);
        jdbcTemplate.update("update stock_opinion set created_at = ? where id = ?", baseTime, initialId);
    }

    @Test
    void readsEntireSelectedEndedCycleInsteadOfOneTimelinePage() throws Exception {
        long earlier = append("UPDATE", "NEUTRAL", "较早变化", null, baseTime.plusSeconds(1));
        long tied = append("REVIEW", "BEARISH", "同一时间的复盘", "待核实", baseTime.plusSeconds(1));
        for (int index = 0; index < 22; index++) {
            append("UPDATE", "NEUTRAL", "周期内观点" + index, null, baseTime.plusSeconds(index + 2));
        }
        lifecycleService.end(ownerId, workspaceId, stockId, cycleId);
        var next = researchService.add(ownerId, workspaceId, stockRequest("00700.HK"));
        assertThat(next.researchCycleId()).isNotEqualTo(cycleId);

        sources(memberToken, workspaceId, stockId, cycleId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.researchCycleId").value(cycleId))
                .andExpect(jsonPath("$.data.cycleStatus").value("ENDED"))
                .andExpect(jsonPath("$.data.scope.coverage").value("COMPLETE"))
                .andExpect(jsonPath("$.data.scope.validOpinionCount").value(25))
                .andExpect(jsonPath("$.data.scope.readOpinionCount").value(25))
                .andExpect(jsonPath("$.data.scope.readAt").isNotEmpty())
                .andExpect(jsonPath("$.data.opinions.length()").value(25))
                .andExpect(jsonPath("$.data.opinions[0].type").value("INITIAL"))
                .andExpect(jsonPath("$.data.opinions[1].id").value(earlier))
                .andExpect(jsonPath("$.data.opinions[2].id").value(tied))
                .andExpect(jsonPath("$.data.opinions[2].type").value("REVIEW"))
                .andExpect(jsonPath("$.data.opinions[1].authorNickname").value("source_member"))
                .andExpect(jsonPath("$.data.opinions[0].password").doesNotExist());
        sources(ownerToken, workspaceId, stockId, next.researchCycleId())
                .andExpect(jsonPath("$.data.opinions.length()").value(1));
        original(memberToken, workspaceId, stockId, cycleId, tied)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("同一时间的复盘"));
    }

    @Test
    void bothReadsRequireAuthenticationAndCurrentMembership() throws Exception {
        mockMvc.perform(get(BASE + "/sources", workspaceId, stockId, cycleId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/opinions/{opinionId}", workspaceId, stockId, cycleId, initialId))
                .andExpect(status().isUnauthorized());
        sources(strangerToken, workspaceId, stockId, cycleId).andExpect(status().isNotFound());
        original(strangerToken, workspaceId, stockId, cycleId, initialId)
                .andExpect(status().isNotFound());
        memberService.remove(ownerId, workspaceId, memberId);
        sources(memberToken, workspaceId, stockId, cycleId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_MEMBERSHIP_INACTIVE"));
        original(memberToken, workspaceId, stockId, cycleId, initialId)
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMismatchedWorkspaceStockCycleAndOpinion() throws Exception {
        long otherWorkspace = workspaceService.create(ownerId, "另一资料空间").workspaceId();
        var otherStock = researchService.add(ownerId, workspaceId, stockRequest("600519.SH"));
        sources(ownerToken, otherWorkspace, stockId, cycleId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_STOCK_NOT_FOUND"));
        sources(ownerToken, workspaceId, otherStock.workspaceStockId(), cycleId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESEARCH_CYCLE_NOT_FOUND"));
        sources(ownerToken, workspaceId, stockId, otherStock.researchCycleId())
                .andExpect(status().isNotFound());
        original(ownerToken, workspaceId, otherStock.workspaceStockId(),
                otherStock.researchCycleId(), initialId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOCK_OPINION_NOT_FOUND"));
        original(ownerToken, otherWorkspace, stockId, cycleId, initialId)
                .andExpect(status().isNotFound());
        sources(ownerToken, workspaceId, stockId, Long.MAX_VALUE)
                .andExpect(status().isNotFound());
        sources(ownerToken, workspaceId, stockId, -1).andExpect(status().isBadRequest());
        original(ownerToken, workspaceId, stockId, cycleId, -1).andExpect(status().isBadRequest());
    }

    @Test
    void withdrawnSourcesAreExcludedAndCannotBeReopened() throws Exception {
        long id = append("UPDATE", "NEUTRAL", "撤回后不能再读到的正文", "隐藏观察条件", baseTime);
        original(memberToken, workspaceId, stockId, cycleId, id)
                .andExpect(jsonPath("$.data.reason").value("撤回后不能再读到的正文"));
        lifecycleService.withdrawOpinion(memberId, workspaceId, stockId, id);
        String response = sources(ownerToken, workspaceId, stockId, cycleId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.validOpinionCount").value(1))
                .andExpect(jsonPath("$.data.opinions.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("撤回后不能再读到的正文", "隐藏观察条件");
        original(ownerToken, workspaceId, stockId, cycleId, id)
                .andExpect(status().isNotFound());
    }

    @Test
    void countLimitReturnsNoTruncatedBodyAndAcceptsExactlyOneHundred() throws Exception {
        for (int index = 0; index < 99; index++) {
            append("UPDATE", "NEUTRAL", "第" + index + "条", null, baseTime);
        }
        sources(ownerToken, workspaceId, stockId, cycleId)
                .andExpect(jsonPath("$.data.scope.coverage").value("COMPLETE"))
                .andExpect(jsonPath("$.data.opinions.length()").value(100));
        long extra = append("UPDATE", "NEUTRAL", "超过读取条数限制", null, baseTime);
        sources(ownerToken, workspaceId, stockId, cycleId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.coverage").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data.scope.validOpinionCount").value(101))
                .andExpect(jsonPath("$.data.scope.readOpinionCount").value(0))
                .andExpect(jsonPath("$.data.opinions").isEmpty());
        original(ownerToken, workspaceId, stockId, cycleId, extra)
                .andExpect(status().isOk());
    }

    @Test
    void textLimitCountsObservationAndUnicodeCodePoints() throws Exception {
        String text = "界".repeat(1000);
        jdbcTemplate.update("update stock_opinion set reason = ?, observation_note = null where id = ?",
                text, initialId);
        for (int index = 0; index < 19; index++) {
            append("UPDATE", "NEUTRAL", text, null, baseTime);
        }
        sources(ownerToken, workspaceId, stockId, cycleId)
                .andExpect(jsonPath("$.data.scope.textCodePointCount").value(20_000))
                .andExpect(jsonPath("$.data.scope.coverage").value("COMPLETE"));
        jdbcTemplate.update("update stock_opinion set observation_note = ? where id = ?", "😀", initialId);
        sources(ownerToken, workspaceId, stockId, cycleId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.textCodePointCount").value(20_001))
                .andExpect(jsonPath("$.data.scope.validOpinionCount").value(20))
                .andExpect(jsonPath("$.data.scope.coverage").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data.opinions").isEmpty());
    }

    @Test
    void cancelledCycleHasCompleteEmptyScope() throws Exception {
        lifecycleService.withdrawOpinion(ownerId, workspaceId, stockId, initialId);
        sources(ownerToken, workspaceId, stockId, cycleId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cycleStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.scope.validOpinionCount").value(0))
                .andExpect(jsonPath("$.data.scope.textCodePointCount").value(0))
                .andExpect(jsonPath("$.data.scope.coverage").value("COMPLETE"))
                .andExpect(jsonPath("$.data.opinions").isEmpty());
        original(ownerToken, workspaceId, stockId, cycleId, initialId)
                .andExpect(status().isNotFound());
    }

    @Test
    void archivedWorkspaceRetainsExistingReadPermission() throws Exception {
        workspaceService.archive(ownerId, workspaceId);
        sources(memberToken, workspaceId, stockId, cycleId).andExpect(status().isOk());
        original(memberToken, workspaceId, stockId, cycleId, initialId).andExpect(status().isOk());
    }

    @Test
    void sourceRecheckAllowsAddedOpinionsAndRejectsWithdrawnOriginalMaterial() {
        var snapshot = sourceService.readCycle(memberId, workspaceId, stockId, cycleId);
        assertThat(sourceService.revalidate(memberId, workspaceId, stockId, cycleId, snapshot)
                .newOpinionsAvailable()).isFalse();
        append("UPDATE", "NEUTRAL", "新增观点", null, baseTime.plusSeconds(1));
        assertThat(sourceService.revalidate(memberId, workspaceId, stockId, cycleId, snapshot)
                .newOpinionsAvailable()).isTrue();
        lifecycleService.withdrawOpinion(ownerId, workspaceId, stockId, initialId);
        assertThatThrownBy(() -> sourceService.revalidate(memberId, workspaceId, stockId, cycleId, snapshot))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED));
    }

    @Test
    void sourceRecheckRejectsPermissionLossChangedBodyAndWrongSnapshotObject() {
        var snapshot = sourceService.readCycle(memberId, workspaceId, stockId, cycleId);
        jdbcTemplate.update("update stock_opinion set reason = ? where id = ?", "改过正文", initialId);
        assertThatThrownBy(() -> sourceService.revalidate(memberId, workspaceId, stockId, cycleId, snapshot))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED));
        memberService.remove(ownerId, workspaceId, memberId);
        assertThatThrownBy(() -> sourceService.revalidate(memberId, workspaceId, stockId, cycleId, snapshot))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> sourceService.revalidate(ownerId, workspaceId, stockId, cycleId + 1, snapshot))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(ErrorCode.RESEARCH_CYCLE_NOT_FOUND));
    }

    @Test
    void sourceRecheckKeepsOriginalSnapshotWhenNewTotalExceedsReadLimit() {
        var snapshot = sourceService.readCycle(memberId, workspaceId, stockId, cycleId);
        for (int index = 0; index < 100; index++) {
            append("UPDATE", "NEUTRAL", "额外观点", null, baseTime.plusSeconds(index + 1));
        }
        assertThat(sourceService.revalidate(memberId, workspaceId, stockId, cycleId, snapshot)
                .newOpinionsAvailable()).isTrue();
    }

    @AfterTransaction
    void confirmsRollbackRemovedFixtures() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from app_user"
                + " where username in ('source_owner', 'source_member', 'source_stranger')", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from workspace where id = ?",
                Long.class, workspaceId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from workspace_stock where workspace_id = ?",
                Long.class, workspaceId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from research_cycle where workspace_stock_id = ?",
                Long.class, stockId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from stock_opinion where research_cycle_id = ?",
                Long.class, cycleId)).isZero();
    }

    private String register(String username) {
        authService.register(new RegisterRequestDTO(username, "TestPass123", username));
        return authService.login(new LoginRequestDTO(username, "TestPass123")).accessToken();
    }

    private AddWorkspaceStockRequestDTO stockRequest(String code) {
        return new AddWorkspaceStockRequestDTO(code, "BULLISH", "初始判断", null, "MEDIUM", null, null);
    }

    private long append(String type, String stance, String reason, String observation, LocalDateTime at) {
        StockOpinion opinion = new StockOpinion();
        opinion.setResearchCycleId(cycleId);
        opinion.setAuthorUserId(memberId);
        opinion.setType(type);
        opinion.setStance(stance);
        opinion.setReason(reason);
        opinion.setObservationNote(observation);
        opinion.setHorizon("MEDIUM");
        opinion.setCreatedAt(at);
        opinionMapper.insert(opinion);
        return opinion.getId();
    }

    private ResultActions sources(String token, long workspace, long stock, long cycle) throws Exception {
        return mockMvc.perform(get(BASE + "/sources", workspace, stock, cycle)
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions original(String token, long workspace, long stock, long cycle, long opinion)
            throws Exception {
        return mockMvc.perform(get(BASE + "/opinions/{opinionId}", workspace, stock, cycle, opinion)
                .header("Authorization", "Bearer " + token));
    }
}
