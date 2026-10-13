package com.stock.system.stock;

import com.stock.system.auth.dto.RegisterRequestDTO;
import com.stock.system.auth.service.AuthService;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.entity.StockOpinion;
import com.stock.system.stock.mapper.StockOpinionMapper;
import com.stock.system.stock.service.StockResearchLifecycleService;
import com.stock.system.stock.service.StockResearchService;
import com.stock.system.stock.service.StockResearchSourceService;
import com.stock.system.support.MySqlIntegrationTest;
import com.stock.system.workspace.service.WorkspaceService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** 两个连接真实提交；每个用例只清理自己创建的 ID，不依赖主线程事务回滚。 */
@SpringBootTest
class StockResearchSourceConcurrencyIntegrationTest extends MySqlIntegrationTest {
    @Autowired private AuthService auth;
    @Autowired private WorkspaceService workspaces;
    @Autowired private StockResearchService research;
    @Autowired private StockResearchLifecycleService lifecycle;
    @Autowired private StockResearchSourceService sources;
    @Autowired private StockOpinionMapper opinions;
    @MockitoSpyBean private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void committedChangeBetweenStatisticsAndBodyKeepsSnapshotThenFreshlyRechecks(boolean withdraw) throws Exception {
        String username = "src_con_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Long user = null;
        Long workspace = null;
        Long stock = null;
        Long cycle = null;
        Long catalogStock = null;
        boolean createdCatalogStock = false;
        CountDownLatch counted = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        AtomicReference<Thread> reader = new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "research-source-concurrency");
            reader.set(thread);
            return thread;
        });
        try {
            user = auth.register(new RegisterRequestDTO(username, "TestPass123", "并发资料测试")).userId();
            workspace = workspaces.create(user, "资料并发快照测试").workspaceId();
            List<Long> existing = jdbc.query("select id from stock where code = ?",
                    (row, index) -> row.getLong("id"), "00700.HK");
            createdCatalogStock = existing.isEmpty();
            var added = research.add(user, workspace,
                    new AddWorkspaceStockRequestDTO("00700.HK", "BULLISH", "并发前初始观点", null, "MEDIUM", null, null));
            stock = added.workspaceStockId(); cycle = added.researchCycleId();
            catalogStock = jdbc.queryForObject("select stock_id from workspace_stock where id = ?", Long.class, stock);
            long initial = jdbc.queryForObject("select id from stock_opinion where research_cycle_id = ?",
                    Long.class, cycle);
            long second = append(user, cycle, "并发前更新观点");
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                String sql = invocation.getArgument(0);
                if (Thread.currentThread() == reader.get() && sql.contains("valid_count")) {
                    counted.countDown();
                    if (!continueRead.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer did not release reader");
                }
                return result;
            }).when(jdbc).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
            Long boundUser = user, boundWorkspace = workspace, boundStock = stock, boundCycle = cycle;
            var future = executor.submit(() -> sources.readCycle(boundUser, boundWorkspace, boundStock, boundCycle));
            assertThat(counted.await(10, TimeUnit.SECONDS)).isTrue();
            long addedDuringRead = append(user, cycle, "统计后提交的新观点");
            if (withdraw) lifecycle.withdrawOpinion(user, workspace, stock, initial);
            continueRead.countDown();
            var snapshot = future.get(10, TimeUnit.SECONDS);
            assertThat(snapshot.scope().validOpinionCount()).isEqualTo(2);
            assertThat(snapshot.opinions()).extracting(source -> source.id()).containsExactly(initial, second)
                    .doesNotContain(addedDuringRead);
            if (withdraw) {
                assertThatThrownBy(() -> sources.revalidate(boundUser, boundWorkspace, boundStock, boundCycle, snapshot))
                        .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.RESEARCH_AGENT_SOURCES_CHANGED));
            } else {
                assertThat(sources.revalidate(user, workspace, stock, cycle, snapshot).newOpinionsAvailable()).isTrue();
            }
        } finally {
            continueRead.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            reset(jdbc);
            if (cycle != null) {
                jdbc.update("delete from research_attention_change where research_cycle_id = ?", cycle);
                jdbc.update("delete from stock_opinion where research_cycle_id = ?", cycle);
                jdbc.update("delete from research_cycle where id = ?", cycle);
            }
            if (stock != null) jdbc.update("delete from workspace_stock where id = ?", stock);
            if (workspace != null) {
                jdbc.update("delete from workspace_invitation where workspace_id = ?", workspace);
                jdbc.update("delete from workspace_member where workspace_id = ?", workspace);
                jdbc.update("delete from workspace where id = ?", workspace);
            }
            if (user != null) jdbc.update("delete from app_user where id = ?", user);
            if (createdCatalogStock && catalogStock != null) {
                jdbc.update("delete from stock where id = ? and not exists"
                        + " (select 1 from workspace_stock where stock_id = ?)", catalogStock, catalogStock);
                assertThat(jdbc.queryForObject("select count(*) from stock where id = ?", Long.class, catalogStock)).isZero();
            }
            assertThat(jdbc.queryForObject("select count(*) from app_user where username = ?", Long.class, username)).isZero();
            if (workspace != null) {
                assertThat(jdbc.queryForObject("select count(*) from workspace where id = ?", Long.class, workspace)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from workspace_member where workspace_id = ?",
                        Long.class, workspace)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from workspace_stock where workspace_id = ?",
                        Long.class, workspace)).isZero();
            }
            if (cycle != null) {
                assertThat(jdbc.queryForObject("select count(*) from research_cycle where id = ?", Long.class, cycle)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from stock_opinion where research_cycle_id = ?",
                        Long.class, cycle)).isZero();
            }
        }
    }

    private long append(Long user, Long cycle, String text) {
        StockOpinion opinion = new StockOpinion();
        opinion.setAuthorUserId(user); opinion.setResearchCycleId(cycle); opinion.setType("UPDATE");
        opinion.setStance("NEUTRAL"); opinion.setReason(text); opinion.setHorizon("MEDIUM");
        opinion.setCreatedAt(LocalDateTime.now());
        opinions.insert(opinion);
        return opinion.getId();
    }
}
