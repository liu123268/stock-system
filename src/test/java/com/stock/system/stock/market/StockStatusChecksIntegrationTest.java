package com.stock.system.stock.market;

import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.StockMapper;
import com.stock.system.stock.directory.StockDirectoryImporter;
import com.stock.system.support.MySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.mybatis.spring.SqlSessionTemplate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class StockStatusChecksIntegrationTest extends MySqlIntegrationTest {
    @Autowired StockMapper mapper;
    @Autowired StockStatusChecks checks;
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSessionTemplate sqlSession;

    @Test
    void savesProviderStatusWithoutPriceAndRejectsOlderResponsesWhileKeepingIdentity() {
        Stock stock = new Stock();
        stock.setCode("999991.SH"); stock.setName("状态验证-" + UUID.randomUUID().toString().substring(0, 8));
        stock.setExchange("SSE"); stock.setCurrency("CNY"); stock.setSource("STATUS_TEST");
        stock.setListingStatus("LISTED"); mapper.insert(stock);
        Long id = stock.getId();
        Instant first = Instant.now().minusSeconds(5);
        checks.observe(stock, new LongbridgeQuote(stock.getCode(), null, null, "Halted", first), first);
        assertThat(mapper.selectById(id).getListingStatus()).isEqualTo("SUSPENDED");
        Instant retired = first.plusSeconds(1);
        checks.observe(stock, new LongbridgeQuote(stock.getCode(), null, Instant.EPOCH, "Delisted", retired), retired);
        Stock confirmed = mapper.selectById(id);
        assertThat(confirmed.getListingStatus()).isEqualTo("DELISTED");
        assertThat(confirmed.getTradeStatus()).isEqualTo("DELISTED");
        assertThat(StockStatusChecks.currentStatus(confirmed)).isEqualTo("DELISTED");
        // A concurrent reader still holds an older directory object. SQL rejects its stale response.
        Stock stale = new Stock(); stale.setId(id); stale.setCode(stock.getCode()); stale.setSource(stock.getSource());
        checks.observe(stale, new LongbridgeQuote(stock.getCode(), null, null, "Normal", first), first);
        assertThat(stale.getListingStatus()).isEqualTo("DELISTED");
        checks.observe(stock, null, retired.plusSeconds(1));
        Stock failed = mapper.selectById(id);
        assertThat(failed.getListingStatus()).isEqualTo("DELISTED");
        assertThat(failed.getStatusCheckedAt()).isEqualTo(confirmed.getStatusCheckedAt());
        assertThat(StockStatusChecks.currentStatus(failed)).isEqualTo("UNKNOWN");
        assertThat(failed.getName()).isEqualTo(stock.getName());
        assertThat(failed.getSource()).isEqualTo("STATUS_TEST");
        assertThat(failed.getId()).isEqualTo(id);
    }

    @Test
    void legacyOrStaleStatusIsUnverifiedAndResumptionFollowsProvider() {
        Stock stock = new Stock(); stock.setCode("999992.SH"); stock.setName("状态回滚验证");
        stock.setExchange("SSE"); stock.setCurrency("CNY"); stock.setSource("STATUS_TEST");
        stock.setListingStatus("LISTED"); mapper.insert(stock);
        assertThat(StockStatusChecks.currentStatus(stock)).isEqualTo("UNKNOWN");
        Instant old = Instant.now().minusSeconds(40 * 3600);
        checks.observe(stock, new LongbridgeQuote(stock.getCode(), null, null, "Halted", old), old);
        assertThat(StockStatusChecks.currentStatus(stock)).isEqualTo("UNKNOWN");
        Instant current = Instant.now();
        checks.observe(stock, new LongbridgeQuote(stock.getCode(), null, null, "Normal", current), current);
        assertThat(StockStatusChecks.currentStatus(stock)).isEqualTo("LISTED");
        assertThat(mapper.selectDueStatusChecks(StockStatusChecks.time(current).toLocalDate().atStartOfDay(), stock.getId() - 1))
                .noneMatch(row -> row.getId().equals(stock.getId()));
        assertThat(mapper.selectDueStatusChecks(StockStatusChecks.time(current).plusSeconds(1), stock.getId() - 1))
                .anyMatch(row -> row.getId().equals(stock.getId()));
    }

    @Test
    void aDirectoryRefreshPreservesConfirmedStateAndQueuesTheNewSnapshot() {
        Stock stock = new Stock(); stock.setCode("999993.SH"); stock.setName("状态目录验证");
        stock.setExchange("SSE"); stock.setCurrency("CNY"); stock.setSource("SSE_DIRECTORY");
        stock.setListingStatus("UNKNOWN"); mapper.insert(stock);
        Instant observed = Instant.now().minusSeconds(5);
        checks.observe(stock, new LongbridgeQuote(stock.getCode(), null, null, "Delisted", observed), observed);
        Stock before = mapper.selectById(stock.getId());
        var directory = new StockDirectoryImporter.Directory(
                OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.ofHours(8)).toString(), "test",
                List.of(new StockDirectoryImporter.Entry(stock.getCode(), stock.getName(), "SSE", "CNY",
                        "SSE_DIRECTORY", stock.getName(), "ztmlyz", "UNKNOWN")));
        jdbc.execute((ConnectionCallback<Integer>) connection -> new StockDirectoryImporter().importRows(connection, directory));
        // The standalone importer uses JDBC. In this mixed test transaction the
        // Mapper session must reread the DB rather than return its earlier object.
        sqlSession.clearCache();
        Stock after = mapper.selectById(stock.getId());
        assertThat(after.getListingStatus()).isEqualTo("DELISTED");
        assertThat(after.getTradeStatus()).isEqualTo(before.getTradeStatus());
        assertThat(after.getStatusCheckedAt()).isEqualTo(before.getStatusCheckedAt());
        assertThat(after.getStatusAttemptedAt()).isEqualTo(before.getStatusAttemptedAt());
        assertThat(after.getLastSyncedAt()).isAfter(after.getStatusAttemptedAt());
        assertThat(mapper.selectDueStatusChecks(StockStatusChecks.time(observed).toLocalDate().atStartOfDay(), stock.getId() - 1))
                .anyMatch(row -> row.getId().equals(stock.getId()));
    }
}
