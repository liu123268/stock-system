package com.stock.system.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.mapper.AppUserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final AppUserMapper appUserMapper;

    public UserService(AppUserMapper appUserMapper) {
        this.appUserMapper = appUserMapper;
    }

    public AppUser findByUsername(String username) {
        return appUserMapper.selectOne(
                new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username)
        );
    }

    public AppUser findById(Long userId) {
        return appUserMapper.selectById(userId);
    }

    public AppUser findByIdForUpdate(Long userId) {
        return appUserMapper.selectByIdForUpdate(userId);
    }

    public boolean usernameExists(String username) {
        return appUserMapper.selectCount(
                new LambdaQueryWrapper<AppUser>().eq(AppUser::getUsername, username)
        ) > 0;
    }

    public void create(AppUser user) {
        appUserMapper.insert(user);
    }

    public void update(AppUser user) {
        appUserMapper.updateById(user);
    }
}
