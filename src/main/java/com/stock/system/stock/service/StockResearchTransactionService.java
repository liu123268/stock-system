package com.stock.system.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class StockResearchTransactionService {

    private final WorkspaceService workspaceService;
    private final StockMapper stockMapper;
    private final WorkspaceStockMapper workspaceStockMapper;
    private final ResearchCycleMapper researchCycleMapper;
    private final StockOpinionMapper stockOpinionMapper;

    public StockResearchTransactionService(
            WorkspaceService workspaceService,
            StockMapper stockMapper,
            WorkspaceStockMapper workspaceStockMapper,
            ResearchCycleMapper researchCycleMapper,
            StockOpinionMapper stockOpinionMapper
    ) {
        this.workspaceService = workspaceService;
        this.stockMapper = stockMapper;
        this.workspaceStockMapper = workspaceStockMapper;
        this.researchCycleMapper = researchCycleMapper;
        this.stockOpinionMapper = stockOpinionMapper;
    }

    @Transactional
    public AddWorkspaceStockResponseVO add(
            Long userId,
            Long workspaceId,
            AddWorkspaceStockRequestDTO request,
            StockQuoteSnapshot snapshot
    ) {
        workspaceService.requireActiveMemberForUpdate(userId, workspaceId);

        Stock stock = upsertStock(snapshot);
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
        opinion.setRiskNote(request.riskNote().trim());
        opinion.setHorizon(request.horizon());
        opinion.setTargetPrice(request.targetPrice());
        opinion.setStopLossPrice(request.stopLossPrice());
        opinion.setCreatedAt(now);
        stockOpinionMapper.insert(opinion);

        return new AddWorkspaceStockResponseVO(workspaceStock.getId(), cycle.getId(), false);
    }

    private Stock upsertStock(StockQuoteSnapshot snapshot) {
        Stock stock = toStock(snapshot);
        stockMapper.upsert(stock);
        return stock;
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
