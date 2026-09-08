package com.stock.system.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认不创建任何账号。只有开发者临时打开开关并提供环境变量时才执行一次。
 */
@Component
@ConditionalOnProperty(name = "stock-system.super-admin.enabled", havingValue = "true")
public class SuperAdminInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SuperAdminInitializer.class);

    private final SuperAdminInitializerService initializerService;
    private final String username;
    private final String password;
    private final String nickname;

    public SuperAdminInitializer(
            SuperAdminInitializerService initializerService,
            @Value("${stock-system.super-admin.username:}") String username,
            @Value("${stock-system.super-admin.password:}") String password,
            @Value("${stock-system.super-admin.nickname:}") String nickname
    ) {
        this.initializerService = initializerService;
        this.username = username;
        this.password = password;
        this.nickname = nickname;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank() || nickname.isBlank()) {
            throw new IllegalStateException("启用超级管理员初始化时必须提供账号、密码和昵称环境变量");
        }

        Long userId = initializerService.initialize(username, password, nickname);
        LOGGER.info("Super administrator initialized for user id {}. Disable the initializer now.", userId);
    }
}
