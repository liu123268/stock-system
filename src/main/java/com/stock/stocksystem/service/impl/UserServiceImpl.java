package com.stock.stocksystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.stock.stocksystem.common.JwtUtil;
import com.stock.stocksystem.entity.User;
import com.stock.stocksystem.mapper.UserMapper;
import com.stock.stocksystem.service.UserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    //注册
    @Override
    public boolean register(User user) {
// ① 建一张空查询单，指定查 user 表
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();

// ② 加条件：username 列 等于 用户填的值
        queryWrapper.eq(User::getUsername, user.getUsername());

// ③ 拿条件单去查，取一条结果
        User existUser = this.getOne(queryWrapper);

        if(existUser != null){
            return false;
        }
//        String salt = String.valueOf(System.currentTimeMillis());
//
//        String rawPassword = user.getPassword();
//        String encryptedPassword = DigestUtils.md5DigestAsHex((rawPassword + salt).getBytes(StandardCharsets.UTF_8));
//        user.setPassword(encryptedPassword);
        user.setPassword(DigestUtils.md5DigestAsHex(
                user.getPassword().getBytes(StandardCharsets.UTF_8)
        ));
        user.setCreateTime(LocalDateTime.now());
        return this.save(user);
    }

    //登录
    @Override
    public String login(User user) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername,user.getUsername());
        User dbUser = this.getOne(queryWrapper);

        if(dbUser == null){
            return null;
        }

//        String encryptedPassword = DigestUtils.md5DigestAsHex(
//                user.getPassword().getBytes(StandardCharsets.UTF_8)
        String encryptedPassword = DigestUtils.md5DigestAsHex(
                user.getPassword().getBytes(StandardCharsets.UTF_8)
        );
        if (!encryptedPassword.equals(dbUser.getPassword())) {
            return null;
        }

        if (!encryptedPassword.equals(dbUser.getPassword())) {
            return null;
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", dbUser.getId());
        claims.put("username", dbUser.getUsername());
        claims.put("department", dbUser.getDepartment());
        claims.put("role", dbUser.getRole());

        String token = JwtUtil.createToken(claims);
        return token;

    }
}