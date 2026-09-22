package com.stock.system.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.user.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

    @Select("SELECT * FROM app_user WHERE id = #{userId} FOR UPDATE")
    AppUser selectByIdForUpdate(@Param("userId") Long userId);
}
