package com.stock.system.stock.controller;

import com.stock.system.common.api.Result;
import com.stock.system.stock.service.StockSearchService;
import com.stock.system.stock.vo.StockSearchResponseVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final StockSearchService stockSearchService;

    public StockController(StockSearchService stockSearchService) {
        this.stockSearchService = stockSearchService;
    }

    @GetMapping("/search")
    public Result<List<StockSearchResponseVO>> search(
            @RequestParam(defaultValue = "") String keyword
    ) {
        return Result.success(stockSearchService.search(keyword));
    }
}
