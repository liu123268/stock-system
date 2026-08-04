package com.stock.stocksystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication
@MapperScan("com.stock.stocksystem.mapper")
public class StockSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(StockSystemApplication.class, args);
    }

}