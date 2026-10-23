package com.stock.system.stock.controller;

import com.stock.system.common.api.Result;
import com.stock.system.stock.service.StockSearchService;
import com.stock.system.stock.vo.StockSearchResponseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
@Tag(name = "05 股票目录")
public class StockController {

    private final StockSearchService stockSearchService;

    public StockController(StockSearchService stockSearchService) {
        this.stockSearchService = stockSearchService;
    }

    @GetMapping("/search")
    @Operation(summary = "搜索股票目录", description = "需要登录；返回当前配置来源的股票身份信息。")
    public Result<List<StockSearchResponseVO>> search(
            @RequestParam(defaultValue = "") String keyword
    ) {
        return Result.success(stockSearchService.search(keyword));
    }
}
