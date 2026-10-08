package com.stock.system.stock.service;

import com.stock.system.stock.market.StockStatusChecks;
import com.stock.system.stock.market.StockStatusInfo;

import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.stock.market.MarketDataProvider;
import com.stock.system.stock.market.StockQuoteSnapshot;
import com.stock.system.stock.vo.StockSearchResponseVO;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.stock.system.stock.mapper.StockMapper;
import java.text.Normalizer;

import java.util.List;

@Service
public class StockSearchService {

    private static final int MAX_KEYWORD_LENGTH = 50;

    private final MarketDataProvider marketDataProvider;
    private final StockMapper stockMapper;
    private final boolean directoryMode;

    public StockSearchService(MarketDataProvider marketDataProvider, StockMapper stockMapper,
                              @Value("${stock-system.market.mode:mock}") String mode) {
        this.marketDataProvider = marketDataProvider;
        this.stockMapper = stockMapper;
        this.directoryMode = "directory".equals(mode);
    }

    public List<StockSearchResponseVO> search(String rawKeyword) {
        String keyword = rawKeyword == null ? "" : Normalizer.normalize(rawKeyword.trim(), Normalizer.Form.NFKC);
        if (keyword.isEmpty() || keyword.codePointCount(0, keyword.length()) > MAX_KEYWORD_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (directoryMode) {
            return stockMapper.searchDirectory(keyword).stream().map(stock -> new StockSearchResponseVO(
                    stock.getCode(), stock.getName(), stock.getExchange(), stock.getCurrency(),
                    StockStatusChecks.currentStatus(stock), null, null, stock.getSource(), false,
                    StockStatusInfo.from(stock))).toList();
        }
        return marketDataProvider.search(keyword).stream()
                .map(this::toResponse)
                .toList();
    }

    private StockSearchResponseVO toResponse(StockQuoteSnapshot snapshot) {
        return new StockSearchResponseVO(
                snapshot.code(),
                snapshot.name(),
                snapshot.exchange(),
                snapshot.currency(),
                snapshot.listingStatus(),
                snapshot.price(),
                snapshot.quoteTime(),
                snapshot.source(),
                snapshot.simulated(),
                snapshot.statusInfo()
        );
    }
}
