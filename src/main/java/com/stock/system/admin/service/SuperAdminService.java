package com.stock.system.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.admin.entity.SuperAdmin;
import com.stock.system.admin.mapper.SuperAdminMapper;
import org.springframework.stereotype.Service;

@Service
public class SuperAdminService {

    private final SuperAdminMapper superAdminMapper;

    public SuperAdminService(SuperAdminMapper superAdminMapper) {
        this.superAdminMapper = superAdminMapper;
    }

    public boolean isSuperAdmin(Long userId) {
        return superAdminMapper.selectCount(
                new LambdaQueryWrapper<SuperAdmin>().eq(SuperAdmin::getUserId, userId)
        ) > 0;
    }
}
