package com.stock.system.stock.market;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.StockMapper;
import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "stock-system.market.mode=longbridge")
@Transactional
@Rollback
@DirtiesContext
@EnabledIfSystemProperty(named = "stock.longbridge.cli.integration", matches = "true")
class LongbridgeMarketDataIntegrationTest extends MySqlIntegrationTest {

    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private MarketDataProvider marketDataProvider;

    @DynamicPropertySource
    static void longbridgeExecutable(DynamicPropertyRegistry registry) {
        registry.add(
                "stock-system.market.longbridge.executable",
                () -> System.getProperty("stock.longbridge.cli.executable", "longbridge")
        );
    }

    @Test
    void enrichesTheTrustedDatabaseIdentityWithARealQuote() {
        Stock existing = stockMapper.selectOne(Wrappers.<Stock>lambdaQuery()
                .eq(Stock::getCode, "600519.SH"));
        if (existing == null) {
            Stock stock = new Stock();
            stock.setCode("600519.SH");
            stock.setName("贵州茅台");
            stock.setExchange("SSE");
            stock.setCurrency("CNY");
            stock.setListingStatus("UNKNOWN");
            stock.setSource("TEST_DIRECTORY");
            stockMapper.insert(stock);
        }

        assertThat(marketDataProvider.search("600519.SH"))
                .singleElement()
                .satisfies(snapshot -> {
                    assertThat(snapshot.code()).isEqualTo("600519.SH");
                    assertThat(snapshot.name()).isEqualTo("贵州茅台");
                    assertThat(snapshot.price()).isPositive();
                    assertThat(snapshot.quoteTime()).isNotNull();
                    assertThat(snapshot.source()).isEqualTo("LONGBRIDGE_OPENAPI");
                    assertThat(snapshot.simulated()).isFalse();
                });
    }

    @AfterTransaction
    void verifiesFixtureWasRolledBack() {
        assertThat(stockMapper.selectCount(Wrappers.<Stock>lambdaQuery()
                .eq(Stock::getSource, "TEST_DIRECTORY"))).isZero();
    }
}
