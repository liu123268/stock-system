package com.stock.stocksystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.stock.stocksystem.entity.StockRecord;
import com.stock.stocksystem.mapper.StockRecordMapper;
import com.stock.stocksystem.service.StockRecordService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class StockRecordServiceImpl extends ServiceImpl<StockRecordMapper, StockRecord> implements StockRecordService {
    @Override
    public boolean add(StockRecord stockRecord, Long userId, String department) {
        // ① 补全记录信息
        stockRecord.setCreateUserId(userId);      // 谁添加的
        stockRecord.setDepartment(department);    // 属于哪个部门
        stockRecord.setCreateTime(LocalDateTime.now()); // 添加时间

        // ② 保存到数据库
        return this.save(stockRecord);
    }

    @Override
    public List<StockRecord> list(String department, String role) {
        LambdaQueryWrapper<StockRecord> queryWrapper = new LambdaQueryWrapper<>();

//        // 如果不是 admin，只查本部门
//        if (!"admin".equals(role)) {
//            queryWrapper.eq(StockRecord::getDepartment, department);
//        }

        // 按创建时间倒序（最新的在前面）
        queryWrapper.orderByDesc(StockRecord::getCreateTime);

        return this.list(queryWrapper);
    }

    @Override
    public boolean delete(Long id, Long userId, String role) {
    StockRecord record = this.getById(id);
    if(record == null){
        return false;
    }
    if(!"admin".equals(role)){
        if(!record.getCreateUserId().equals(userId)){
            return false;
        }
    }
        return this.removeById(id);
    }

    @Override
    public boolean update(StockRecord stockRecord, Long userId, String role) {
        StockRecord dbRecord = this.getById(stockRecord.getId());
        if(dbRecord == null){
            return false;
        }

        //
        if(!"admin".equals(role)){
            if(!dbRecord.getCreateUserId().equals(userId)){
                return false;
            }
        }
        dbRecord.setRecordedPrice(stockRecord.getRecordedPrice());
        dbRecord.setNote(stockRecord.getNote());

        return this.updateById(dbRecord);
    }

}
