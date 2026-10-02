package com.stock.system.admin.service;

import com.stock.system.admin.entity.SuperAdmin;
import com.stock.system.admin.mapper.SuperAdminMapper;
import com.stock.system.user.entity.AppUser;
import com.stock.system.user.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class SuperAdminInitializerService {

    private final SuperAdminMapper superAdminMapper;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public SuperAdminInitializerService(
            SuperAdminMapper superAdminMapper,
            UserService userService,
            PasswordEncoder passwordEncoder
    ) {
        this.superAdminMapper = superAdminMapper;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Long initialize(String rawUsername, String rawPassword, String rawNickname) {
        if (superAdminMapper.selectCount(null) > 0) {
            throw new IllegalStateException("超级管理员已经存在，不能重复初始化");
        }

        String username = rawUsername.trim().toLowerCase(Locale.ROOT);
        String nickname = rawNickname.trim();
        if (!username.matches("[a-z0-9_]{2,20}")) {
            throw new IllegalStateException("超级管理员登录账号必须是2到20位小写字母、数字或下划线");
        }
        if (rawPassword.length() < 6 || rawPassword.length() > 64) {
            throw new IllegalStateException("超级管理员密码长度必须在6到64个字符之间");
        }
        if (nickname.isEmpty() || nickname.length() > 20) {
            throw new IllegalStateException("超级管理员昵称长度必须在1到20个字符之间");
        }
        if (userService.usernameExists(username)) {
            throw new IllegalStateException("超级管理员登录账号已经被普通账号使用");
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setNickname(nickname);
        user.setStatus("ACTIVE");
        userService.create(user);

        SuperAdmin superAdmin = new SuperAdmin();
        superAdmin.setId(1);
        superAdmin.setUserId(user.getId());
        superAdminMapper.insert(superAdmin);
        return user.getId();
    }
}
