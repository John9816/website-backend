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
    public static final String CLIENT_MUSIC_THIRD_PARTY = "musicThirdPartyHttpClient";
    public static final String EXECUTOR_MUSIC_RESOLVER = "musicResolverExecutor";
    public static final String CLIENT_QUICK = "quickHttpClient";
    public static final String CLIENT_BOOKS = "booksHttpClient";

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
    public OkHttpClient okHttpClient(ConnectionPool sharedConnectionPool, Dispatcher sharedDispatcher,
                                     UpstreamMetricsInterceptor upstreamMetricsInterceptor) {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(sharedConnectionPool)
                .dispatcher(sharedDispatcher)
                .addInterceptor(upstreamMetricsInterceptor)
                .retryOnConnectionFailure(true)
                .build();
    }

    @Bean(name = CLIENT_AI_UPSTREAM)
    public OkHttpClient aiUpstreamHttpClient(OkHttpClient okHttpClient) {
        // Content-factory writer prompts are large and MiMo/reasoning models can exceed 3 minutes.
        // Keep this aligned with the local reverse-proxy timeout (600s).
        return okHttpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(620, TimeUnit.SECONDS)
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

    @Bean(name = CLIENT_MUSIC_THIRD_PARTY)
    public OkHttpClient musicThirdPartyHttpClient(UpstreamMetricsInterceptor upstreamMetricsInterceptor) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(16);
        dispatcher.setMaxRequestsPerHost(8);
        return new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .writeTimeout(4, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(10, 2, TimeUnit.MINUTES))
                .dispatcher(dispatcher)
                .addInterceptor(upstreamMetricsInterceptor)
                .retryOnConnectionFailure(false)
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

    @Bean(name = CLIENT_BOOKS)
    public OkHttpClient booksHttpClient(OkHttpClient okHttpClient) {
        // Book sources are untrusted upstreams. Redirects are followed by the
        // source client itself only after each target has passed host checks.
        return okHttpClient.newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
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

    @Bean(name = EXECUTOR_MUSIC_RESOLVER, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor musicResolverExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setKeepAliveSeconds(30);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("music-resolver-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
