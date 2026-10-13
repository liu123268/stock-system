package com.stock.system.stock;

import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.dto.ResearchSourceFilter;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

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
    private long strangerId;
    private Long catalogStockId;
    private boolean createdCatalogStock;
    private long workspaceId;
    private long stockId;
    private long cycleId;
    private long initialId;
    private LocalDateTime baseTime;

    @BeforeEach
    void createRollbackOnlyResearch() {
        ownerToken = register("source_owner");
        ownerId = userService.findByUsername("source_owner").getId();
        memberToken = register("source_member");
        memberId = userService.findByUsername("source_member").getId();
        strangerToken = register("source_stranger");
        strangerId = userService.findByUsername("source_stranger").getId();
        workspaceId = workspaceService.create(ownerId, "周期资料测试空间").workspaceId();
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(memberId);
        member.setStatus("ACTIVE");
        memberMapper.insert(member);
        createdCatalogStock = jdbcTemplate.queryForObject("select count(*) from stock where code = ?",
                Long.class, "00700.HK") == 0;
        var added = researchService.add(ownerId, workspaceId, stockRequest("00700.HK"));
        stockId = added.workspaceStockId();
        catalogStockId = jdbcTemplate.queryForObject("select stock_id from workspace_stock where id = ?", Long.class, stockId);
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

    @Test
    void memberAndInclusiveCalendarDatesDefineTheActualReturnedScope() throws Exception {
        long first = append("UPDATE", "NEUTRAL", "成员当天观点", null, baseTime.plusSeconds(1));
        long last = append("UPDATE", "BEARISH", "当天末尾观点", null,
                baseTime.toLocalDate().atTime(23, 59, 59));
        append("UPDATE", "BULLISH", "次日不在范围", null, baseTime.toLocalDate().plusDays(1).atStartOfDay());
        mockMvc.perform(get(BASE + "/sources", workspaceId, stockId, cycleId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("authorUserId", Long.toString(memberId))
                        .param("dateFrom", baseTime.toLocalDate().toString())
                        .param("dateTo", baseTime.toLocalDate().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.validOpinionCount").value(2))
                .andExpect(jsonPath("$.data.scope.filter.authorUserId").value(memberId))
                .andExpect(jsonPath("$.data.scope.filter.dateFrom").value(baseTime.toLocalDate().toString()))
                .andExpect(jsonPath("$.data.opinions[0].id").value(first))
                .andExpect(jsonPath("$.data.opinions[1].id").value(last));
    }

    @Test
    void invalidOrForeignAuthorAndInvalidDateRangeAreRejected() throws Exception {
        for (long author : new long[]{-1L, userService.findByUsername("source_stranger").getId()}) {
            mockMvc.perform(get(BASE + "/sources", workspaceId, stockId, cycleId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .param("authorUserId", Long.toString(author)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get(BASE + "/sources", workspaceId, stockId, cycleId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("dateFrom", "2026-10-14").param("dateTo", "2026-10-13"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(BASE + "/sources", workspaceId, stockId, cycleId)
                        .header("Authorization", "Bearer " + ownerToken).param("dateFrom", "bad-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shrinkingExcessiveCycleToOneAuthorReturnsCompleteMaterials() throws Exception {
        for (int index = 0; index < 100; index++) {
            append("UPDATE", "NEUTRAL", "另一成员观点", null, baseTime);
        }
        sources(ownerToken, workspaceId, stockId, cycleId)
                .andExpect(jsonPath("$.data.scope.coverage").value("LIMIT_EXCEEDED"));
        mockMvc.perform(get(BASE + "/sources", workspaceId, stockId, cycleId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("authorUserId", Long.toString(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope.coverage").value("COMPLETE"))
                .andExpect(jsonPath("$.data.scope.validOpinionCount").value(1))
                .andExpect(jsonPath("$.data.opinions[0].id").value(initialId));
    }

    @Test
    void revalidationOnlyFlagsNewMaterialWithinTheSelectedScope() {
        long selected = append("UPDATE", "NEUTRAL", "选定成员原文", null, baseTime);
        var filter = new ResearchSourceFilter(memberId, baseTime.toLocalDate(), baseTime.toLocalDate());
        var snapshot = sourceService.readCycle(ownerId, workspaceId, stockId, cycleId, filter);
        append("UPDATE", "NEUTRAL", "日期范围外的新观点", null, baseTime.plusDays(1));
        assertThat(sourceService.revalidate(ownerId, workspaceId, stockId, cycleId, snapshot)
                .newOpinionsAvailable()).isFalse();
        append("UPDATE", "BULLISH", "范围内新观点", null, baseTime.plusSeconds(1));
        assertThat(sourceService.revalidate(ownerId, workspaceId, stockId, cycleId, snapshot)
                .newOpinionsAvailable()).isTrue();
        lifecycleService.withdrawOpinion(memberId, workspaceId, stockId, selected);
        assertThatThrownBy(() -> sourceService.revalidate(ownerId, workspaceId, stockId, cycleId, snapshot))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                        .isEqualTo(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED));
    }

    @Test
    @Transactional(propagation = Propagation.NEVER)
    void contextProvidesServerQuoteAndSourcesWithOriginalAuthorizationBoundaries() throws Exception {
        mockMvc.perform(get(BASE + "/agent/context", workspaceId, stockId, cycleId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.materials.researchCycleId").value(cycleId))
                .andExpect(jsonPath("$.data.quote.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.quote.currency").value("HKD"))
                .andExpect(jsonPath("$.data.quote.simulated").value(true));
        mockMvc.perform(get(BASE + "/agent/context", workspaceId, stockId, cycleId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/agent/context", workspaceId, stockId, cycleId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/agent/context", workspaceId, stockId, cycleId + 1)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @Transactional(propagation = Propagation.NEVER)
    void excessiveContextReturnsRangeAndQuoteWithoutTruncatedOpinionBodies() throws Exception {
        for (int index = 0; index < 100; index++) {
            append("UPDATE", "NEUTRAL", "超限观点", null, baseTime);
        }
        mockMvc.perform(get(BASE + "/agent/context", workspaceId, stockId, cycleId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.materials.scope.coverage").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data.materials.opinions").isEmpty())
                .andExpect(jsonPath("$.data.quote.status").value("AVAILABLE"));
    }

    @AfterEach
    void cleanOnlyCommittedContextFixtures() {
        if (TestTransaction.isActive()) return;
        if (cycleId != 0) {
            jdbcTemplate.update("delete from research_attention_change where research_cycle_id = ?", cycleId);
            jdbcTemplate.update("delete from stock_opinion where research_cycle_id = ?", cycleId);
            jdbcTemplate.update("delete from research_cycle where id = ?", cycleId);
        }
        if (stockId != 0) jdbcTemplate.update("delete from workspace_stock where id = ?", stockId);
        if (workspaceId != 0) {
            jdbcTemplate.update("delete from workspace_invitation where workspace_id = ?", workspaceId);
            jdbcTemplate.update("delete from workspace_member where workspace_id = ?", workspaceId);
            jdbcTemplate.update("delete from workspace where id = ?", workspaceId);
        }
        for (long user : new long[]{ownerId, memberId, strangerId}) {
            if (user != 0) jdbcTemplate.update("delete from app_user where id = ?", user);
        }
        if (createdCatalogStock && catalogStockId != null) {
            jdbcTemplate.update("delete from stock where id = ? and not exists"
                    + " (select 1 from workspace_stock where stock_id = ?)", catalogStockId, catalogStockId);
        }
        confirmsRollbackRemovedFixtures();
        if (createdCatalogStock && catalogStockId != null) {
            assertThat(jdbcTemplate.queryForObject("select count(*) from stock where id = ?",
                    Long.class, catalogStockId)).isZero();
        }
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
