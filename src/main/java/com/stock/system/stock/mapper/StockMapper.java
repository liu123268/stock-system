package com.stock.system.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.stock.entity.Stock;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface StockMapper extends BaseMapper<Stock> {

    @Select("""
            SELECT * FROM stock
            WHERE source <> 'LOCAL_MOCK'
              AND (LOCATE(UPPER(#{keyword}), CONVERT(code USING utf8mb4)) > 0
                   OR LOCATE(#{keyword}, name) > 0
                   OR LOCATE(#{keyword}, search_aliases) > 0
                   OR (LOWER(#{keyword}) REGEXP '^[a-z]+$'
                       AND CONVERT(pinyin_initials USING utf8mb4) LIKE CONCAT(LOWER(#{keyword}), '%')))
            ORDER BY CASE
                         WHEN CONVERT(code USING utf8mb4) = UPPER(#{keyword}) THEN 0
                         WHEN name = #{keyword} OR search_aliases = #{keyword} THEN 1
                         WHEN CONVERT(pinyin_initials USING utf8mb4) = LOWER(#{keyword}) THEN 2
                         WHEN LOWER(#{keyword}) REGEXP '^[a-z]+$'
                              AND CONVERT(pinyin_initials USING utf8mb4) LIKE CONCAT(LOWER(#{keyword}), '%') THEN 3
                         ELSE 4
                     END,
                     code
            LIMIT 30
            """)
    List<Stock> searchDirectory(@Param("keyword") String keyword);

    @Select("""
            SELECT * FROM stock
            WHERE code = #{code}
              AND source <> 'LOCAL_MOCK'
            FOR UPDATE
            """)
    Stock selectDirectoryByCodeForUpdate(@Param("code") String code);

    @Update("UPDATE stock SET listing_status = #{listingStatus} WHERE id = #{stockId}")
    int updateListingStatus(
            @Param("stockId") Long stockId,
            @Param("listingStatus") String listingStatus
    );

    @Insert("""
            INSERT INTO stock (code, name, exchange, currency, listing_status, source)
            VALUES (#{code}, #{name}, #{exchange}, #{currency}, #{listingStatus}, #{source})
            ON DUPLICATE KEY UPDATE
                id = LAST_INSERT_ID(id)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIfAbsent(Stock stock);
}
