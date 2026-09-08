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
                                  "riskNote": "估值波动风险",
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
                  "riskNote": "估值波动风险",
                  "horizon": "MEDIUM",
                  "targetPrice": 700.0000,
                  "stopLossPrice": 500.0000,
                  "name": "前端伪造名称",
                  "price": 1,
                  "source": "FAKE"
                }
                """.formatted(code);
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
