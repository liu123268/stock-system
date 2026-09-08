package com.stock.system.config;

import com.stock.system.common.api.Result;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 只在 test profile 存在，供浏览器测试在产生写入前核对后端的真实数据库。
 */
@RestController
@Profile("test")
@RequestMapping("/api/test/environment")
public class TestEnvironmentController {

    private final DataSource dataSource;

    public TestEnvironmentController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping
    public Result<TestEnvironmentResponseVO> getEnvironment() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return Result.success(new TestEnvironmentResponseVO("test", connection.getCatalog()));
        }
    }

    public record TestEnvironmentResponseVO(String profile, String database) {
    }
}
