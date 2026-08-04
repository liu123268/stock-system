package com.stock.stocksystem.controller;


import com.stock.stocksystem.common.JwtUtil;
import com.stock.stocksystem.common.Result;
import com.stock.stocksystem.entity.StockRecord;
import com.stock.stocksystem.service.StockRecordService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stock")
public class StockRecordController {
    @Autowired
    private StockRecordService stockRecordService;

    @PostMapping("/add")
    public Result add(@RequestBody StockRecord stockRecord,
                      @RequestHeader("token")String token)
    {
        Claims claims = JwtUtil.parseToken(token);

        Long userId = claims.get("userId",Long.class);
        String department = claims.get("department",String.class);

        boolean success = stockRecordService.add(stockRecord,userId,department);

        if(success){
            return Result.success("添加成功");

        }else{
            return Result.error("添加失败");
        }
    }
    // 查询股票列表
    @GetMapping("/list")
    public Result list(@RequestHeader("token") String token) {
        // 从 Token 里取出用户信息
        Claims claims = JwtUtil.parseToken(token);
        String department = claims.get("department", String.class);
        String role = claims.get("role", String.class);

        // 调用 Service 查询
        List<StockRecord> list = stockRecordService.list(department, role);

        return Result.success(list);
    }
    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable Long id,
                         @RequestHeader("token") String token) {
        Claims claims = JwtUtil.parseToken(token);
        Long userId = claims.get("userId", Long.class);
        String role = claims.get("role", String.class);

        boolean success = stockRecordService.delete(id, userId, role);
        if (success) {
            return Result.success("删除成功");
        } else {
            return Result.error("无权限或记录不存在");
        }
    }
    @PutMapping("/update")
    public Result update(@RequestBody StockRecord stockRecord,
                         @RequestHeader("token") String token) {
        Claims claims = JwtUtil.parseToken(token);
        Long userId = claims.get("userId", Long.class);
        String role = claims.get("role", String.class);

        boolean success = stockRecordService.update(stockRecord, userId, role);
        if (success) {
            return Result.success("修改成功");
        } else {
            return Result.error("无权限或记录不存在");


        }
    }
}
