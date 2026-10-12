package com.stock.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan({
        "com.stock.system.user.mapper",
        "com.stock.system.workspace.mapper",
        "com.stock.system.admin.mapper",
        "com.stock.system.stock.mapper"
})
public class StockSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockSystemApplication.class, args);
    }

}
