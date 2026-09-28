package com.stock.system.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.dto.AddWorkspaceStockRequestDTO;
import com.stock.system.stock.entity.ResearchCycle;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.entity.StockOpinion;
import com.stock.system.stock.entity.WorkspaceStock;
import com.stock.system.stock.mapper.ResearchCycleMapper;
import com.stock.system.stock.mapper.StockMapper;
import com.stock.system.stock.mapper.StockOpinionMapper;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.market.StockQuoteSnapshot;
import com.stock.system.stock.vo.AddWorkspaceStockResponseVO;
import com.stock.system.stock.vo.UpdateAttentionLevelResponseVO;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class StockResearchTransactionService {

    private final WorkspaceService workspaceService;
    private final StockMapper stockMapper;
    private final WorkspaceStockMapper workspaceStockMapper;
    private final ResearchCycleMapper researchCycleMapper;
    private final StockOpinionMapper stockOpinionMapper;
    private final JdbcTemplate jdbcTemplate;

    public StockResearchTransactionService(
            WorkspaceService workspaceService,
            StockMapper stockMapper,
            WorkspaceStockMapper workspaceStockMapper,
            ResearchCycleMapper researchCycleMapper,
            StockOpinionMapper stockOpinionMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.workspaceService = workspaceService;
        this.stockMapper = stockMapper;
        this.workspaceStockMapper = workspaceStockMapper;
        this.researchCycleMapper = researchCycleMapper;
        this.stockOpinionMapper = stockOpinionMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public AddWorkspaceStockResponseVO add(
            Long userId,
            Long workspaceId,
            AddWorkspaceStockRequestDTO request,
            StockQuoteSnapshot snapshot
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);

        requireResearchableStatus(snapshot);
        Stock stock = resolveStock(snapshot);
        WorkspaceStock workspaceStock = findOrCreateWorkspaceStock(
                workspaceId,
                stock.getId(),
                userId
        );
        ResearchCycle activeCycle = findActiveCycle(workspaceStock.getId());
        if (activeCycle != null) {
            return new AddWorkspaceStockResponseVO(
                    workspaceStock.getId(),
                    activeCycle.getId(),
                    true
            );
        }

        LocalDateTime now = LocalDateTime.now();
        ResearchCycle cycle = new ResearchCycle();
        cycle.setWorkspaceStockId(workspaceStock.getId());
        cycle.setStatus("ACTIVE");
        cycle.setAttentionLevel("NORMAL");
        cycle.setStartedByUserId(userId);
        cycle.setStartedAt(now);
        cycle.setStartPrice(snapshot.price());
        cycle.setQuoteTime(snapshot.quoteTime());
        cycle.setQuoteSource(snapshot.source());
        cycle.setIsSimulated(snapshot.simulated());
        researchCycleMapper.insert(cycle);

        StockOpinion opinion = new StockOpinion();
        opinion.setResearchCycleId(cycle.getId());
        opinion.setAuthorUserId(userId);
        opinion.setType("INITIAL");
        opinion.setStance(request.stance());
        opinion.setReason(request.reason().trim());
        opinion.setObservationNote(normalizeOptionalText(request.observationNote()));
        opinion.setHorizon(request.horizon());
        opinion.setTargetPrice(request.targetPrice());
        opinion.setStopLossPrice(request.stopLossPrice());
        opinion.setCreatedAt(now);
        stockOpinionMapper.insert(opinion);

        return new AddWorkspaceStockResponseVO(workspaceStock.getId(), cycle.getId(), false);
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Transactional
    public UpdateAttentionLevelResponseVO updateAttentionLevel(
            Long userId,
            Long workspaceId,
            Long workspaceStockId,
            Long researchCycleId,
            String attentionLevel
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);
        ResearchCycle cycle = researchCycleMapper.selectActiveForUpdate(
                workspaceId,
                workspaceStockId
        );
        if (cycle == null) {
            if (workspaceStockMapper.selectStockIdentity(workspaceId, workspaceStockId) == null) {
                throw new BusinessException(ErrorCode.WORKSPACE_STOCK_NOT_FOUND);
            }
            throw new BusinessException(ErrorCode.RESEARCH_CYCLE_CHANGED);
        }
        if (!cycle.getId().equals(researchCycleId)) {
            throw new BusinessException(ErrorCode.RESEARCH_CYCLE_CHANGED);
        }

        if (!attentionLevel.equals(cycle.getAttentionLevel())) {
            String previousLevel = cycle.getAttentionLevel();
            cycle.setAttentionLevel(attentionLevel);
            researchCycleMapper.updateById(cycle);
            jdbcTemplate.update("""
                    INSERT INTO research_attention_change
                        (research_cycle_id, actor_user_id, from_level, to_level, changed_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, cycle.getId(), userId, previousLevel, attentionLevel, LocalDateTime.now());
        }
        return new UpdateAttentionLevelResponseVO(
                workspaceStockId,
                cycle.getId(),
                cycle.getAttentionLevel()
        );
    }

    private Stock resolveStock(StockQuoteSnapshot snapshot) {
        if (!snapshot.simulated()) {
            Stock stock = stockMapper.selectDirectoryByCodeForUpdate(snapshot.code());
            if (stock == null) {
                throw new BusinessException(ErrorCode.STOCK_NOT_AVAILABLE);
            }
            if (!snapshot.listingStatus().equals(stock.getListingStatus())) {
                stockMapper.updateListingStatus(stock.getId(), snapshot.listingStatus());
                stock.setListingStatus(snapshot.listingStatus());
            }
            return stock;
        }
        Stock stock = toStock(snapshot);
        stockMapper.insertIfAbsent(stock);
        return stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getCode, snapshot.code()));
    }

    private void requireResearchableStatus(StockQuoteSnapshot snapshot) {
        if ("DELISTED".equals(snapshot.listingStatus())) {
            throw new BusinessException(ErrorCode.STOCK_NOT_AVAILABLE);
        }
        if (!"LISTED".equals(snapshot.listingStatus())
                && !"SUSPENDED".equals(snapshot.listingStatus())) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_UNAVAILABLE);
        }
    }

    private Stock toStock(StockQuoteSnapshot snapshot) {
        Stock stock = new Stock();
        stock.setCode(snapshot.code());
        stock.setName(snapshot.name());
        stock.setExchange(snapshot.exchange());
        stock.setCurrency(snapshot.currency());
        stock.setListingStatus(snapshot.listingStatus());
        stock.setSource(snapshot.source());
        return stock;
    }

    private WorkspaceStock findOrCreateWorkspaceStock(Long workspaceId, Long stockId, Long userId) {
        WorkspaceStock workspaceStock = workspaceStockMapper.selectOne(
                new LambdaQueryWrapper<WorkspaceStock>()
                        .eq(WorkspaceStock::getWorkspaceId, workspaceId)
                        .eq(WorkspaceStock::getStockId, stockId)
        );
        if (workspaceStock != null) {
            return workspaceStock;
        }

        workspaceStock = new WorkspaceStock();
        workspaceStock.setWorkspaceId(workspaceId);
        workspaceStock.setStockId(stockId);
        workspaceStock.setFirstAddedByUserId(userId);
        workspaceStock.setFirstAddedAt(LocalDateTime.now());
        workspaceStockMapper.insert(workspaceStock);
        return workspaceStock;
    }

    private ResearchCycle findActiveCycle(Long workspaceStockId) {
        return researchCycleMapper.selectOne(
                new LambdaQueryWrapper<ResearchCycle>()
                        .eq(ResearchCycle::getWorkspaceStockId, workspaceStockId)
                        .eq(ResearchCycle::getStatus, "ACTIVE")
        );
    }
}
