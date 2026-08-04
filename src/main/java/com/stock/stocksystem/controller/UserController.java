package com.stock.stocksystem.controller;

import com.stock.stocksystem.common.Result;
import com.stock.stocksystem.entity.User;
import com.stock.stocksystem.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public Result register(@RequestBody User user){
        boolean success = userService.register(user);
        if(success){
            return Result.success( "注册成功");

        }else{
            return Result.success("用户名已存在");
        }
    }
    @PostMapping("/login")
    public Result login(@RequestBody User user){
        String token = userService.login(user);
        if(token == null){
            return Result.error("用户名或密码错误");
        }else{
            return Result.success(token);
        }
    }
}
