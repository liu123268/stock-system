package com.stock.system.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 浏览器测试写入数据前必须匿名核对测试库；这条放行规则只随 test profile 存在。
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
public class TestEnvironmentSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain testEnvironmentSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/test/environment")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
