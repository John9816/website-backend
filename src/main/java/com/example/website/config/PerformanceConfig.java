package com.example.website.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 性能优化配置：启用异步处理和定时任务
 */
@Configuration
@EnableAsync
@EnableScheduling
public class PerformanceConfig {
}
