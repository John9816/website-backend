package com.example.website.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库性能优化配置
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.example.website.module")
public class DatabaseOptimizationConfig {

    /**
     * 数据库连接初始化：设置会话级优化参数
     */
    @Bean
    @ConditionalOnProperty(name = "app.database.optimize", havingValue = "true", matchIfMissing = true)
    public DatabaseConnectionInitializer databaseConnectionInitializer(DataSource dataSource) {
        return new DatabaseConnectionInitializer(dataSource);
    }

    public static class DatabaseConnectionInitializer {
        public DatabaseConnectionInitializer(DataSource dataSource) {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                // 设置MySQL会话级优化参数（仅影响当前连接，不修改全局配置）
                // 这些参数会在HikariCP创建新连接时通过connectionInitSql应用
                stmt.execute("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'");

            } catch (SQLException e) {
                // 初始化失败不阻塞启动，记录日志即可
                System.err.println("Database optimization parameters could not be set: " + e.getMessage());
            }
        }
    }
}
