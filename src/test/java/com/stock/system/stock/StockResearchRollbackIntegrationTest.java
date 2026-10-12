package com.stock.system.stock;

import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.stock.entity.StockOpinion;
import com.stock.system.stock.mapper.StockMapper;
import com.stock.system.stock.mapper.StockOpinionMapper;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.mapper.ResearchCycleMapper;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.user.service.UserService;
import com.stock.system.workspace.service.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StockResearchRollbackIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private WorkspaceService workspaceService;
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private WorkspaceStockMapper workspaceStockMapper;
    @Autowired
    private ResearchCycleMapper researchCycleMapper;
    @MockitoSpyBean
    private StockOpinionMapper stockOpinionMapper;

    @Test
    void opinionInsertFailureRollsBackAllEarlierStockResearchWrites() throws Exception {
        authService.register(new RegisterRequestDTO(
                "research_rollback",
                "TestPass123",
                "事务回滚测试"
        ));
        Long userId = userService.findByUsername("research_rollback").getId();
        Long workspaceId = workspaceService.create(userId, "股票事务回滚空间").workspaceId();
        String token = authService.login(
                new LoginRequestDTO("research_rollback", "TestPass123")
        ).accessToken();

        doThrow(new RuntimeException("controlled opinion insert failure"))
                .when(stockOpinionMapper)
                .insert(any(StockOpinion.class));

        mockMvc.perform(post("/api/workspaces/{workspaceId}/stocks", workspaceId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "600519.SH",
                                  "stance": "BULLISH",
                                  "reason": "验证事务回滚",
                                  "observationNote": "受控失败",
                                  "horizon": "MEDIUM"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));

        TestTransaction.flagForRollback();
        TestTransaction.end();

        assertThat(stockMapper.selectCount(null)).isZero();
        assertThat(workspaceStockMapper.selectCount(null)).isZero();
        assertThat(researchCycleMapper.selectCount(null)).isZero();
        assertThat(stockOpinionMapper.selectCount(null)).isZero();
    }
}
