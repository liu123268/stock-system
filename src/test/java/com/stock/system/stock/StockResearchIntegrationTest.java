package com.stock.system.stock;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.stock.entity.ResearchCycle;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.entity.StockOpinion;
import com.stock.system.stock.entity.WorkspaceStock;
import com.stock.system.stock.mapper.ResearchCycleMapper;
import com.stock.system.stock.mapper.StockMapper;
import com.stock.system.stock.mapper.StockOpinionMapper;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.entity.WorkspaceMember;
import com.stock.system.workspace.mapper.WorkspaceMemberMapper;
import com.stock.system.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class StockResearchIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private WorkspaceMemberMapper workspaceMemberMapper;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private WorkspaceStockMapper workspaceStockMapper;
    @Autowired
    private ResearchCycleMapper researchCycleMapper;
    @Autowired
    private StockOpinionMapper stockOpinionMapper;

    private Long ownerId;
    private Long memberId;
    private Long workspaceId;
    private String ownerToken;
    private String memberToken;
    private String strangerToken;

    @BeforeEach
    void createUsersAndWorkspace() {
        ownerToken = registerAndLogin("research_owner");
        memberToken = registerAndLogin("research_member");
        strangerToken = registerAndLogin("research_stranger");
        ownerId = userService.findByUsername("research_owner").getId();
        memberId = userService.findByUsername("research_member").getId();
        workspaceId = workspaceService.create(ownerId, "股票研究测试空间").workspaceId();

        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspaceId);
        member.setUserId(memberId);
        member.setStatus("ACTIVE");
        workspaceMemberMapper.insert(member);
    }

    @Test
    void ownerAddIsAtomicUsesTrustedQuoteAndDuplicateReturnsExistingIds() throws Exception {
        JsonNode first = addStock(ownerToken, workspaceId, "00700.HK")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyActive").value(false))
                .andReturnJson();

        Long workspaceStockId = first.path("data").path("workspaceStockId").longValue();
        Long researchCycleId = first.path("data").path("researchCycleId").longValue();
        Stock stock = stockMapper.selectOne(
                new LambdaQueryWrapper<Stock>().eq(Stock::getCode, "00700.HK")
        );
        ResearchCycle cycle = researchCycleMapper.selectById(researchCycleId);
        StockOpinion opinion = stockOpinionMapper.selectOne(
                new LambdaQueryWrapper<StockOpinion>()
                        .eq(StockOpinion::getResearchCycleId, researchCycleId)
        );

        assertThat(stock.getName()).isEqualTo("腾讯控股");
        assertThat(stock.getCurrency()).isEqualTo("HKD");
        assertThat(stock.getSource()).isEqualTo("LOCAL_MOCK");
        assertThat(cycle.getWorkspaceStockId()).isEqualTo(workspaceStockId);
        assertThat(cycle.getStartPrice()).isEqualByComparingTo("612.0000");
        assertThat(cycle.getIsSimulated()).isTrue();
        assertThat(opinion.getAuthorUserId()).isEqualTo(ownerId);
        assertThat(opinion.getType()).isEqualTo("INITIAL");

        addStock(ownerToken, workspaceId, "00700.hk")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspaceStockId").value(workspaceStockId))
                .andExpect(jsonPath("$.data.researchCycleId").value(researchCycleId))
                .andExpect(jsonPath("$.data.alreadyActive").value(true));

        assertThat(countWorkspaceStocks(workspaceId)).isEqualTo(1);
        assertThat(researchCycleMapper.selectCount(null)).isEqualTo(1);
        assertThat(stockOpinionMapper.selectCount(null)).isEqualTo(1);
    }

    @Test
    void addingResearchKeepsExistingDirectoryIdentity() throws Exception {
        Stock directoryStock = new Stock();
        directoryStock.setCode("600519.SH");
        directoryStock.setName("目录中的贵州茅台");
        directoryStock.setExchange("SSE");
        directoryStock.setCurrency("CNY");
        directoryStock.setListingStatus("UNKNOWN");
        directoryStock.setSource("SSE_DIRECTORY");
        stockMapper.insert(directoryStock);

        addStock(ownerToken, workspaceId, "600519.SH").andExpect(status().isOk());

        Stock stored = stockMapper.selectById(directoryStock.getId());
        assertThat(stored.getName()).isEqualTo("目录中的贵州茅台");
        assertThat(stored.getListingStatus()).isEqualTo("UNKNOWN");
        assertThat(stored.getSource()).isEqualTo("SSE_DIRECTORY");
    }

    @Test
    void activeMemberCanAddButStrangerAndArchivedWorkspaceCannot() throws Exception {
        addStock(memberToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyActive").value(false));

        addStock(strangerToken, workspaceId, "300750.SZ")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));

        workspaceService.archive(ownerId, workspaceId);
        addStock(ownerToken, workspaceId, "300750.SZ")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ARCHIVED"));

        assertThat(countWorkspaceStocks(workspaceId)).isEqualTo(1);
    }

    @Test
    void sameStockCanBeResearchedIndependentlyInDifferentWorkspaces() throws Exception {
        addStock(ownerToken, workspaceId, "300750.SZ").andExpect(status().isOk());
        Long otherWorkspaceId = workspaceService.create(ownerId, "第二股票研究空间").workspaceId();
        addStock(ownerToken, otherWorkspaceId, "300750.SZ").andExpect(status().isOk());

        assertThat(stockMapper.selectCount(
                new LambdaQueryWrapper<Stock>().eq(Stock::getCode, "300750.SZ")
        )).isEqualTo(1);
        assertThat(countWorkspaceStocks(workspaceId)).isEqualTo(1);
        assertThat(countWorkspaceStocks(otherWorkspaceId)).isEqualTo(1);
        assertThat(researchCycleMapper.selectCount(null)).isEqualTo(2);
    }

    @Test
    void unavailableQuoteAndInvalidOpinionWriteNothing() throws Exception {
        addStock(ownerToken, workspaceId, "000000.SH")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MARKET_QUOTE_UNAVAILABLE"));

        mockMvc.perform(post("/api/workspaces/{workspaceId}/stocks", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "600519.SH",
                                  "stance": "BULLISH",
                                  "reason": "   ",
                                  "observationNote": "估值波动风险",
                                  "horizon": "MEDIUM"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(stockMapper.selectCount(null)).isZero();
        assertThat(workspaceStockMapper.selectCount(null)).isZero();
        assertThat(researchCycleMapper.selectCount(null)).isZero();
        assertThat(stockOpinionMapper.selectCount(null)).isZero();
    }

    @Test
    void bearishOpinionAcceptsAnOmittedObservationNote() throws Exception {
        mockMvc.perform(post("/api/workspaces/{workspaceId}/stocks", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "600519.SH",
                                  "stance": "BEARISH",
                                  "reason": "当前估值与增长不匹配",
                                  "horizon": "MEDIUM"
                                }
                                """))
                .andExpect(status().isOk());

        StockOpinion opinion = stockOpinionMapper.selectOne(
                new LambdaQueryWrapper<StockOpinion>()
                        .eq(StockOpinion::getType, "INITIAL")
        );
        assertThat(opinion.getStance()).isEqualTo("BEARISH");
        assertThat(opinion.getObservationNote()).isNull();
    }

    @Test
    void activeMemberCanListSharedStocksButStrangerCannotAndArchiveRemainsReadable()
            throws Exception {
        addStock(ownerToken, workspaceId, "00700.HK").andExpect(status().isOk());
        addStock(memberToken, workspaceId, "600519.SH").andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/{workspaceId}/stocks", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].code").value("600519.SH"))
                .andExpect(jsonPath("$.data[0].authorNickname").value("research_member"))
                .andExpect(jsonPath("$.data[0].stance").value("BULLISH"))
                .andExpect(jsonPath("$.data[0].startPrice").value(1488.8800))
                .andExpect(jsonPath("$.data[0].quoteSource").value("LOCAL_MOCK"))
                .andExpect(jsonPath("$.data[0].simulated").value(true));

        mockMvc.perform(get("/api/workspaces/{workspaceId}/stocks", workspaceId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));

        workspaceService.archive(ownerId, workspaceId);
        mockMvc.perform(get("/api/workspaces/{workspaceId}/stocks", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void activeMemberSwitchesAttentionBothWaysButStrangerAndArchivedWorkspaceCannot()
            throws Exception {
        JsonNode added = addStock(ownerToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andReturnJson();
        long workspaceStockId = added.path("data").path("workspaceStockId").longValue();
        long cycleId = added.path("data").path("researchCycleId").longValue();
        String path = "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/attention";

        mockMvc.perform(patch(path, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"researchCycleId":%d,"attentionLevel":"FOCUS"}
                                """.formatted(cycleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.researchCycleId").value(cycleId))
                .andExpect(jsonPath("$.data.attentionLevel").value("FOCUS"));

        mockMvc.perform(get("/api/workspaces/{workspaceId}/stocks", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].attentionLevel").value("FOCUS"));

        mockMvc.perform(patch(path, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"researchCycleId":%d,"attentionLevel":"NORMAL"}
                                """.formatted(cycleId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attentionLevel").value("NORMAL"));

        mockMvc.perform(patch(path, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"researchCycleId":%d,"attentionLevel":"IMPORTANT"}
                                """.formatted(cycleId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(patch(path, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + strangerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"researchCycleId":%d,"attentionLevel":"FOCUS"}
                                """.formatted(cycleId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));

        workspaceService.archive(ownerId, workspaceId);
        mockMvc.perform(patch(path, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"researchCycleId":%d,"attentionLevel":"FOCUS"}
                                """.formatted(cycleId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ARCHIVED"));

        assertThat(researchCycleMapper.selectById(cycleId).getAttentionLevel())
                .isEqualTo("NORMAL");
    }

    @Test
    void membersBuildAResearchTimelineEndAndReopenWithoutChangingOldCycle()
            throws Exception {
        JsonNode firstAdd = addStock(ownerToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andReturnJson();
        long workspaceStockId = firstAdd.path("data").path("workspaceStockId").longValue();
        long firstCycleId = firstAdd.path("data").path("researchCycleId").longValue();
        String opinionsPath = "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/opinions";

        JsonNode memberOpinion = new ResultActionsJson(
                mockMvc.perform(post(opinionsPath, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opinionBody(firstCycleId, "UPDATE", "NEUTRAL", "估值进入观察区间"))),
                objectMapper
        ).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorNickname").value("research_member"))
                .andReturnJson();
        long opinionId = memberOpinion.path("data").path("id").longValue();

        mockMvc.perform(delete(opinionsPath + "/{opinionId}", workspaceId, workspaceStockId, opinionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STOCK_OPINION_ACCESS_DENIED"));

        mockMvc.perform(delete(opinionsPath + "/{opinionId}", workspaceId, workspaceStockId, opinionId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());

        mockMvc.perform(post(opinionsPath, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opinionBody(firstCycleId, "REVIEW", "BEARISH", "风险已经上升")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("REVIEW"));

        mockMvc.perform(post(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/end",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"researchCycleId\":" + firstCycleId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ENDED"));

        mockMvc.perform(post(opinionsPath, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opinionBody(firstCycleId, "UPDATE", "BULLISH", "不应写入旧周期")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESEARCH_CYCLE_CHANGED"));

        mockMvc.perform(get("/api/workspaces/{workspaceId}/stocks/ended", workspaceId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].workspaceStockId").value(workspaceStockId))
                .andExpect(jsonPath("$.data[0].researchCycleId").value(firstCycleId));

        JsonNode secondAdd = addStock(memberToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyActive").value(false))
                .andReturnJson();
        long secondCycleId = secondAdd.path("data").path("researchCycleId").longValue();
        assertThat(secondCycleId).isNotEqualTo(firstCycleId);

        mockMvc.perform(post(opinionsPath, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opinionBody(firstCycleId, "UPDATE", "BULLISH", "旧页面不能写入新周期")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESEARCH_CYCLE_CHANGED"));

        mockMvc.perform(patch(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/attention",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"researchCycleId\":" + firstCycleId
                                + ",\"attentionLevel\":\"FOCUS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESEARCH_CYCLE_CHANGED"));

        mockMvc.perform(post(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/end",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"researchCycleId\":" + firstCycleId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESEARCH_CYCLE_CHANGED"));

        assertThat(researchCycleMapper.selectById(secondCycleId).getStatus()).isEqualTo("ACTIVE");
        assertThat(researchCycleMapper.selectById(secondCycleId).getAttentionLevel())
                .isEqualTo("NORMAL");

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/research",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cycles.length()").value(2))
                .andExpect(jsonPath("$.data.cycles[0].id").value(secondCycleId))
                .andExpect(jsonPath("$.data.cycles[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.cycles[1].id").value(firstCycleId))
                .andExpect(jsonPath("$.data.cycles[1].status").value("ENDED"))
                .andExpect(jsonPath("$.data.cycles[1].opinions.length()").value(3))
                .andExpect(jsonPath("$.data.cycles[1].opinions[1].withdrawn").value(true))
                .andExpect(jsonPath("$.data.cycles[1].opinions[1].reason").doesNotExist())
                .andExpect(jsonPath("$.data.cycles[1].opinions[2].canWithdraw").value(false));

        assertThat(countWorkspaceStocks(workspaceId)).isEqualTo(1);
        assertThat(researchCycleMapper.selectCount(null)).isEqualTo(2);
        assertThat(stockOpinionMapper.selectCount(null)).isEqualTo(4);
    }

    @Test
    void withdrawingInitialOpinionCancelsCycleAndKeepsAuditHistory() throws Exception {
        JsonNode added = addStock(ownerToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andReturnJson();
        long workspaceStockId = added.path("data").path("workspaceStockId").longValue();
        long researchCycleId = added.path("data").path("researchCycleId").longValue();
        long initialOpinionId = stockOpinionMapper.selectOne(
                new LambdaQueryWrapper<StockOpinion>()
                        .eq(StockOpinion::getResearchCycleId, researchCycleId)
                        .eq(StockOpinion::getType, "INITIAL")
        ).getId();

        mockMvc.perform(delete(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/opinions/{opinionId}",
                        workspaceId,
                        workspaceStockId,
                        initialOpinionId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/workspaces/{workspaceId}/stocks", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/workspaces/{workspaceId}/stocks/ended", workspaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].workspaceStockId").value(workspaceStockId))
                .andExpect(jsonPath("$.data[0].status").value("CANCELLED"));

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/research",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cycles[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cycles[0].opinions[0].withdrawn").value(true))
                .andExpect(jsonPath("$.data.cycles[0].opinions[0].reason").doesNotExist())
                .andExpect(jsonPath("$.data.cycles[0].opinions[0].observationNote").doesNotExist());

        assertThat(researchCycleMapper.selectById(researchCycleId).getStatus())
                .isEqualTo("CANCELLED");
        assertThat(researchCycleMapper.selectCount(null)).isEqualTo(1);
        assertThat(stockOpinionMapper.selectCount(null)).isEqualTo(1);
    }

    @Test
    void opinionCannotBeWithdrawnAfterFiveMinutes() throws Exception {
        JsonNode added = addStock(ownerToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andReturnJson();
        long workspaceStockId = added.path("data").path("workspaceStockId").longValue();
        long researchCycleId = added.path("data").path("researchCycleId").longValue();
        StockOpinion initial = stockOpinionMapper.selectOne(
                new LambdaQueryWrapper<StockOpinion>()
                        .eq(StockOpinion::getResearchCycleId, researchCycleId)
                        .eq(StockOpinion::getType, "INITIAL")
        );
        initial.setCreatedAt(java.time.LocalDateTime.now().minusMinutes(6));
        stockOpinionMapper.updateById(initial);

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/research",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cycles[0].opinions[0].canWithdraw").value(false));

        mockMvc.perform(delete(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/opinions/{opinionId}",
                        workspaceId,
                        workspaceStockId,
                        initial.getId())
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_OPINION_WITHDRAW_WINDOW_EXPIRED"));

        assertThat(researchCycleMapper.selectById(researchCycleId).getStatus())
                .isEqualTo("ACTIVE");
        assertThat(stockOpinionMapper.selectById(initial.getId()).getWithdrawnAt()).isNull();
    }

    @Test
    void archivedWorkspaceNeverOffersOpinionWithdrawal() throws Exception {
        JsonNode added = addStock(ownerToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andReturnJson();
        long workspaceStockId = added.path("data").path("workspaceStockId").longValue();
        workspaceService.archive(ownerId, workspaceId);

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/research",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cycles[0].opinions[0].canWithdraw").value(false));
    }

    @Test
    void memberLoadsCandlesticksOnlyForAStockAlreadyInTheWorkspace() throws Exception {
        JsonNode added = addStock(ownerToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andReturnJson();
        long workspaceStockId = added.path("data").path("workspaceStockId").longValue();

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/candlesticks",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .param("period", "year")
                        .param("count", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("600519.SH"))
                .andExpect(jsonPath("$.data.period").value("year"))
                .andExpect(jsonPath("$.data.adjustment").value("none"))
                .andExpect(jsonPath("$.data.source").value("LOCAL_MOCK"))
                .andExpect(jsonPath("$.data.simulated").value(true))
                .andExpect(jsonPath("$.data.nextBefore").isNotEmpty())
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.candlesticks.length()").value(40))
                .andExpect(jsonPath("$.data.candlesticks[0].open").isNumber())
                .andExpect(jsonPath("$.data.candlesticks[0].close").isNumber());

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/candlesticks",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + strangerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_NOT_FOUND"));

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/candlesticks",
                        workspaceId,
                        workspaceStockId + 999)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKSPACE_STOCK_NOT_FOUND"));
    }

    @Test
    void rejectsUnsupportedCandlestickPeriodAndOversizedCount() throws Exception {
        JsonNode added = addStock(ownerToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andReturnJson();
        long workspaceStockId = added.path("data").path("workspaceStockId").longValue();
        String path = "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/candlesticks";

        mockMvc.perform(get(path, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("period", "2m"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get(path, workspaceId, workspaceStockId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("count", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void memberLoadsMinuteCandlesticksAndHistoricalIntradayForOneExpandedStock()
            throws Exception {
        JsonNode added = addStock(ownerToken, workspaceId, "600519.SH")
                .andExpect(status().isOk())
                .andReturnJson();
        long workspaceStockId = added.path("data").path("workspaceStockId").longValue();

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/candlesticks",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .param("period", "1m")
                        .param("count", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.period").value("1m"))
                .andExpect(jsonPath("$.data.candlesticks.length()").value(240));

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/intraday",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .param("date", "2026-09-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tradeDate").value("2026-09-04"))
                .andExpect(jsonPath("$.data.updating").value(false))
                .andExpect(jsonPath("$.data.points.length()").value(242))
                .andExpect(jsonPath("$.data.points[0].averagePrice").isNumber());

        mockMvc.perform(get(
                        "/api/workspaces/{workspaceId}/stocks/{workspaceStockId}/intraday",
                        workspaceId,
                        workspaceStockId)
                        .header("Authorization", "Bearer " + memberToken)
                        .param("date", "2099-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private ResultActionsJson addStock(String token, Long targetWorkspaceId, String code)
            throws Exception {
        return new ResultActionsJson(
                mockMvc.perform(post("/api/workspaces/{workspaceId}/stocks", targetWorkspaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(code))),
                objectMapper
        );
    }

    private String requestBody(String code) {
        return """
                {
                  "code": "%s",
                  "stance": "BULLISH",
                  "reason": "长期品牌优势",
                  "observationNote": "估值波动风险",
                  "horizon": "MEDIUM",
                  "targetPrice": 700.0000,
                  "stopLossPrice": 500.0000,
                  "name": "前端伪造名称",
                  "price": 1,
                  "source": "FAKE"
                }
                """.formatted(code);
    }

    private String opinionBody(long researchCycleId, String type, String stance, String reason) {
        return """
                {
                  "researchCycleId": %d,
                  "type": "%s",
                  "stance": "%s",
                  "reason": "%s",
                  "observationNote": "需要继续验证基本面变化",
                  "horizon": "MEDIUM",
                  "targetPrice": 1600.0000,
                  "stopLossPrice": 1300.0000
                }
                """.formatted(researchCycleId, type, stance, reason);
    }

    private long countWorkspaceStocks(Long targetWorkspaceId) {
        return workspaceStockMapper.selectCount(
                new LambdaQueryWrapper<WorkspaceStock>()
                        .eq(WorkspaceStock::getWorkspaceId, targetWorkspaceId)
        );
    }

    private String registerAndLogin(String username) {
        authService.register(new RegisterRequestDTO(username, "TestPass123", username));
        return authService.login(new LoginRequestDTO(username, "TestPass123")).accessToken();
    }

    private static class ResultActionsJson {
        private final org.springframework.test.web.servlet.ResultActions actions;
        private final ObjectMapper objectMapper;

        private ResultActionsJson(
                org.springframework.test.web.servlet.ResultActions actions,
                ObjectMapper objectMapper
        ) {
            this.actions = actions;
            this.objectMapper = objectMapper;
        }

        private ResultActionsJson andExpect(
                org.springframework.test.web.servlet.ResultMatcher matcher
        ) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        private JsonNode andReturnJson() throws Exception {
            return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
        }
    }
}
