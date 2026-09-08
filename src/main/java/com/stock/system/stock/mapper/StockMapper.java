package com.stock.system.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.stock.entity.Stock;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface StockMapper extends BaseMapper<Stock> {

    @Select("""
            SELECT * FROM stock
            WHERE source <> 'LOCAL_MOCK'
              AND (LOCATE(UPPER(#{keyword}), CONVERT(code USING utf8mb4)) > 0
                   OR LOCATE(#{keyword}, name) > 0
                   OR LOCATE(#{keyword}, search_aliases) > 0)
            ORDER BY (CONVERT(code USING utf8mb4) = UPPER(#{keyword})) DESC, code
            LIMIT 30
            """)
    List<Stock> searchDirectory(@Param("keyword") String keyword);

    @Insert("""
            INSERT INTO stock (code, name, exchange, currency, listing_status, source)
            VALUES (#{code}, #{name}, #{exchange}, #{currency}, #{listingStatus}, #{source})
            ON DUPLICATE KEY UPDATE
                id = LAST_INSERT_ID(id),
                name = VALUES(name),
                exchange = VALUES(exchange),
                currency = VALUES(currency),
                listing_status = VALUES(listing_status),
                source = VALUES(source)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(Stock stock);
}
