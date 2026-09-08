package com.stock.system.stock;

import com.stock.system.auth.dto.LoginRequestDTO;
import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.stock.directory.StockDirectoryImporter;
import com.stock.system.stock.directory.StockDirectoryImporter.Directory;
import com.stock.system.stock.directory.StockDirectoryImporter.Entry;
import com.stock.system.stock.mapper.StockMapper;
import com.stock.system.stock.market.MarketDataProvider;
import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "stock-system.market.mode=directory")
@AutoConfigureMockMvc
@Transactional
class StockDirectoryIntegrationTest extends MySqlIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired StockMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired MarketDataProvider provider;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    private final StockDirectoryImporter importer = new StockDirectoryImporter();

    private Directory directory(Entry... entries) {
        return new Directory("2026-09-08T01:00:00Z", "test fixture", List.of(entries));
    }
    private Entry hk(String name) {
        return new Entry("00700.HK", name, "HKEX", "HKD", "HKEX_DIRECTORY", "騰訊控股 TENCENT", "UNKNOWN");
    }
    private void insert(Directory directory) throws Exception {
        importer.importRows(DataSourceUtils.getConnection(dataSource), directory);
        // The standalone JDBC importer normally runs in another process.
        // This test shares a MyBatis session, so invalidate its earlier read.
        sqlSession.clearCache();
    }

    @Test
    void searchesDatabaseBySimplifiedTraditionalEnglishAndQualifiedCode() throws Exception {
        insert(directory(hk("腾讯控股"), new Entry("920002.BJ", "万达轴承", "BSE", "CNY", "CNINFO_DIRECTORY", "万达轴承", "UNKNOWN")));
        for (String query : List.of("腾讯", "騰訊", "tencent", "00700.hk")) {
            assertThat(mapper.searchDirectory(query)).singleElement().satisfies(s -> assertThat(s.getCode()).isEqualTo("00700.HK"));
        }
        assertThat(mapper.searchDirectory("920002.BJ")).singleElement().satisfies(s -> assertThat(s.getExchange()).isEqualTo("BSE"));
        assertThat(mapper.searchDirectory("%")).isEmpty();
        assertThat(mapper.searchDirectory("' OR 1=1 --")).isEmpty();
    }

    @Test
    void repeatedImportKeepsIdAndVerifiedStatusWhileUpdatingNames() throws Exception {
        insert(directory(hk("旧名称")));
        long id = mapper.searchDirectory("00700").get(0).getId();
        jdbc.update("UPDATE stock SET listing_status='SUSPENDED' WHERE id=?", id);
        insert(directory(hk("腾讯控股")));
        assertThat(mapper.searchDirectory("00700")).singleElement().satisfies(s -> {
            assertThat(s.getId()).isEqualTo(id);
            assertThat(s.getName()).isEqualTo("腾讯控股");
            assertThat(s.getListingStatus()).isEqualTo("SUSPENDED");
            assertThat(s.getLastSyncedAt()).isNotNull();
        });
    }

    @Test
    void validatesEntireBatchBeforeAnyWriteAndRejectsDuplicates() {
        Entry bad = new Entry("920002.SZ", "错误市场", "BSE", "CNY", "CNINFO_DIRECTORY", "", "UNKNOWN");
        assertThatThrownBy(() -> insert(directory(hk("腾讯控股"), bad))).isInstanceOf(IllegalArgumentException.class);
        assertThat(mapper.searchDirectory("00700")).isEmpty();
        assertThatThrownBy(() -> insert(directory(hk("腾讯控股"), hk("腾讯控股")))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apiRequiresLoginAndReturnsDirectoryWithoutFabricatedPrices() throws Exception {
        insert(directory(hk("腾讯控股")));
        mvc.perform(get("/api/stocks/search").param("keyword", "腾讯")).andExpect(status().isUnauthorized());
        auth.register(new RegisterRequestDTO("directory_test_user", "Directory123", "目录测试"));
        String token = auth.login(new LoginRequestDTO("directory_test_user", "Directory123")).accessToken();
        mvc.perform(get("/api/stocks/search").header("Authorization", "Bearer " + token).param("keyword", "腾讯"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("00700.HK"))
                .andExpect(jsonPath("$.data[0].price").isEmpty())
                .andExpect(jsonPath("$.data[0].quoteTime").isEmpty())
                .andExpect(jsonPath("$.data[0].listingStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.data[0].simulated").value(false));
        assertThat(provider.getLatestQuote("00700.HK")).isEmpty();
        assertThat(provider.getLatestQuote("600519.SH")).isEmpty();
    }
}
