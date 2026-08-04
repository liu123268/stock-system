package com.stock.stocksystem.service;

import com.stock.stocksystem.entity.User;
import com.baomidou.mybatisplus.extension.service.IService;

public interface UserService extends IService<User> {
        boolean register(User user);
        String login (User user);
}
