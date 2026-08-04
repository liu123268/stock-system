package com.stock.stocksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.stock.stocksystem.entity.StockRecord;

import java.util.List;

public interface StockRecordService extends IService<StockRecord> {
    boolean add(StockRecord stockRecord, Long userId, String department);

    List<StockRecord> list(String department,String role);

    boolean delete(Long id, Long userId, String role);

    boolean update(StockRecord stockRecord, Long userId, String role);


}
