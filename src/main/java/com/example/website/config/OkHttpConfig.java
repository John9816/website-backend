package com.example.website.config;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class OkHttpConfig {

    public static final String CLIENT_AI_UPSTREAM = "aiUpstreamHttpClient";
    public static final String CLIENT_IMAGE_UPSTREAM = "imageUpstreamHttpClient";
    public static final String CLIENT_MUSIC = "musicHttpClient";
    public static final String CLIENT_QUICK = "quickHttpClient";

    @Bean
    public ConnectionPool sharedConnectionPool() {
        return new ConnectionPool(50, 5, TimeUnit.MINUTES);
    }

    @Bean
    public Dispatcher sharedDispatcher() {
        Dispatcher d = new Dispatcher();
        d.setMaxRequests(128);
        d.setMaxRequestsPerHost(32);
        return d;
    }

    @Bean
    @Primary
    public OkHttpClient okHttpClient(ConnectionPool sharedConnectionPool, Dispatcher sharedDispatcher) {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(sharedConnectionPool)
                .dispatcher(sharedDispatcher)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Bean(name = CLIENT_AI_UPSTREAM)
    public OkHttpClient aiUpstreamHttpClient(OkHttpClient okHttpClient) {
        return okHttpClient.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Bean(name = CLIENT_IMAGE_UPSTREAM)
    public OkHttpClient imageUpstreamHttpClient(OkHttpClient okHttpClient) {
        return okHttpClient.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(360, TimeUnit.SECONDS)
                .build();
    }

    @Bean(name = CLIENT_MUSIC)
    public OkHttpClient musicHttpClient(OkHttpClient okHttpClient) {
        return okHttpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    @Bean(name = CLIENT_QUICK)
    public OkHttpClient quickHttpClient(OkHttpClient okHttpClient) {
        return okHttpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build();
    }

    @Bean(name = "aiStreamExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor aiStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("ai-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "imageGenExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor imageGenExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("image-gen-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "imageGenSubExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor imageGenSubExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("image-gen-sub-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "imagePersistExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor imagePersistExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("image-persist-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
