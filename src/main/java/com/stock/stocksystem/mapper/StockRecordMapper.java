package com.stock.stocksystem.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.stocksystem.entity.StockRecord;
import org.apache.ibatis.annotations.Mapper;

import java.util.stream.BaseStream;

@Mapper
public interface StockRecordMapper extends BaseMapper<StockRecord> {

}
