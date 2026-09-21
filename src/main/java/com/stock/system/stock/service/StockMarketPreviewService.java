package com.stock.system.stock.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.entity.Stock;
import com.stock.system.stock.mapper.StockMapper;
import com.stock.system.stock.mapper.WorkspaceStockMapper;
import com.stock.system.stock.mapper.WorkspaceStockTrackingRow;
import com.stock.system.stock.market.MarketDataProvider;
import com.stock.system.stock.market.StockQuoteSnapshot;
import com.stock.system.stock.vo.StockCandlestickSeriesVO;
import com.stock.system.stock.vo.StockIntradaySeriesVO;
import com.stock.system.stock.vo.StockMarketPreviewResponseVO;
import com.stock.system.workspace.service.WorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class StockMarketPreviewService {

    private static final String MOCK_MODE = "mock";
    private static final String LOCAL_MOCK_SOURCE = "LOCAL_MOCK";
    private static final Pattern STANDARD_CODE =
            Pattern.compile("^[0-9A-Z]{1,16}\\.(SH|SZ|BJ|HK)$");

    private final WorkspaceService workspaceService;
    private final StockMapper stockMapper;
    private final WorkspaceStockMapper workspaceStockMapper;
    private final MarketDataProvider marketDataProvider;
    private final StockMarketSeriesService marketSeriesService;
    private final String marketMode;

    public StockMarketPreviewService(
            WorkspaceService workspaceService,
            StockMapper stockMapper,
            WorkspaceStockMapper workspaceStockMapper,
            MarketDataProvider marketDataProvider,
            StockMarketSeriesService marketSeriesService,
            @Value("${stock-system.market.mode:mock}") String marketMode
    ) {
        this.workspaceService = workspaceService;
        this.stockMapper = stockMapper;
        this.workspaceStockMapper = workspaceStockMapper;
        this.marketDataProvider = marketDataProvider;
        this.marketSeriesService = marketSeriesService;
        this.marketMode = marketMode;
    }

    public StockMarketPreviewResponseVO detail(Long userId, Long workspaceId, String rawCode) {
        workspaceService.detail(userId, workspaceId);
        ResolvedStock resolved = requireTrustedStock(rawCode);
        StockQuoteSnapshot quote = resolved.trustedSnapshot()
                .or(() -> marketDataProvider.getLatestQuote(resolved.stock().getCode()))
                .orElse(null);
        WorkspaceStockTrackingRow tracking = trackingState(workspaceId, resolved.stockId());

        Stock stock = resolved.stock();
        String trackingState = tracking == null
                ? "NOT_TRACKED"
                : tracking.getActiveResearchCycleId() == null ? "ENDED" : "ACTIVE";
        return new StockMarketPreviewResponseVO(
                stock.getCode(),
                stock.getName(),
                stock.getExchange(),
                stock.getCurrency(),
                quote == null ? stock.getListingStatus() : quote.listingStatus(),
                quote == null ? null : quote.price(),
                quote == null ? null : quote.quoteTime(),
                quote == null ? stock.getSource() : quote.source(),
                quote != null && quote.simulated(),
                trackingState,
                tracking == null ? null : tracking.getWorkspaceStockId(),
                tracking == null ? null : tracking.getActiveResearchCycleId()
        );
    }

    public StockCandlestickSeriesVO candlesticks(
            Long userId,
            Long workspaceId,
            String rawCode,
            String rawPeriod,
            int count,
            Instant before
    ) {
        workspaceService.detail(userId, workspaceId);
        Stock stock = requireTrustedStock(rawCode).stock();
        return marketSeriesService.getCandlesticks(stock, rawPeriod, count, before);
    }

    public StockIntradaySeriesVO intraday(
            Long userId,
            Long workspaceId,
            String rawCode,
            LocalDate requestedDate
    ) {
        workspaceService.detail(userId, workspaceId);
        Stock stock = requireTrustedStock(rawCode).stock();
        return marketSeriesService.getIntraday(stock, requestedDate);
    }

    private ResolvedStock requireTrustedStock(String rawCode) {
        String code = normalizeCode(rawCode);
        if (MOCK_MODE.equalsIgnoreCase(marketMode)) {
            StockQuoteSnapshot snapshot = marketDataProvider.getLatestQuote(code)
                    .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND));
            Stock existing = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                    .eq(Stock::getCode, code));
            return new ResolvedStock(
                    existing == null ? null : existing.getId(),
                    toStock(snapshot),
                    Optional.of(snapshot)
            );
        }

        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getCode, code)
                .ne(Stock::getSource, LOCAL_MOCK_SOURCE));
        if (stock == null) {
            throw new BusinessException(ErrorCode.STOCK_NOT_FOUND);
        }
        return new ResolvedStock(stock.getId(), stock, Optional.empty());
    }

    private String normalizeCode(String rawCode) {
        if (rawCode == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        String code = Normalizer.normalize(rawCode.trim(), Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT);
        if (!STANDARD_CODE.matcher(code).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return code;
    }

    private WorkspaceStockTrackingRow trackingState(Long workspaceId, Long stockId) {
        return stockId == null ? null : workspaceStockMapper.selectTrackingState(workspaceId, stockId);
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

    private record ResolvedStock(
            Long stockId,
            Stock stock,
            Optional<StockQuoteSnapshot> trustedSnapshot
    ) {
    }
}
