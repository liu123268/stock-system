package com.stock.system.stock;

import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class StockSearchIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    private String accessToken;

    @BeforeEach
    void registerSearchUser() {
        authService.register(new RegisterRequestDTO(
                "stock_search_user",
                "SearchPass123",
                "股票搜索测试"
        ));
        accessToken = authService.login(
                new LoginRequestDTO("stock_search_user", "SearchPass123")
        ).accessToken();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/stocks/search").queryParam("keyword", "茅台"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void returnsTrustedSimulatedQuoteFields() throws Exception {
        mockMvc.perform(get("/api/stocks/search")
                        .header("Authorization", "Bearer " + accessToken)
                        .queryParam("keyword", "  00700  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("00700.HK"))
                .andExpect(jsonPath("$.data[0].name").value("腾讯控股"))
                .andExpect(jsonPath("$.data[0].currency").value("HKD"))
                .andExpect(jsonPath("$.data[0].source").value("LOCAL_MOCK"))
                .andExpect(jsonPath("$.data[0].simulated").value(true));
    }

    @Test
    void rejectsBlankKeywordAndReturnsEmptyListForNoMatch() throws Exception {
        mockMvc.perform(get("/api/stocks/search")
                        .header("Authorization", "Bearer " + accessToken)
                        .queryParam("keyword", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/stocks/search")
                        .header("Authorization", "Bearer " + accessToken)
                        .queryParam("keyword", "不存在"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
