package com.stock.system.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.stock.system.user.entity.AppUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
