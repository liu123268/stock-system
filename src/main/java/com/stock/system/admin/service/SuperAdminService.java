package com.stock.system.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.system.admin.entity.SuperAdmin;
import com.stock.system.admin.mapper.SuperAdminMapper;
import com.stock.system.common.api.ErrorCode;
import com.stock.system.common.exception.BusinessException;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class SuperAdminService {

    private final SuperAdminMapper superAdminMapper;
    private final UserService userService;

    public SuperAdminService(SuperAdminMapper superAdminMapper, UserService userService) {
        this.superAdminMapper = superAdminMapper;
        this.userService = userService;
    }

    public boolean isSuperAdmin(Long userId) {
        return superAdminMapper.selectCount(
                new LambdaQueryWrapper<SuperAdmin>().eq(SuperAdmin::getUserId, userId)
        ) > 0;
    }

    public void requireSuperAdmin(Long userId) {
        AppUser user = userService.findById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!isSuperAdmin(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
