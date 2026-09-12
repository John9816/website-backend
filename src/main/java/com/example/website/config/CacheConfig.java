package com.example.website.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_SYS_CONFIG = "sysConfig";
    public static final String CACHE_AUTH_USER = "authUser";
    public static final String CACHE_KB_DOC_SHARE = "kbDocShare";
    public static final String CACHE_MUSIC_SEARCH = "musicSearch";
    public static final String CACHE_MUSIC_PLAY = "musicPlay";
    public static final String CACHE_MUSIC_LYRIC = "musicLyric";
    public static final String CACHE_MUSIC_PLAYLIST = "musicPlaylist";
    public static final String CACHE_MUSIC_TOPLIST = "musicToplist";
    public static final String CACHE_NAV_CATEGORIES = "navCategories";
    public static final String CACHE_NAV_LINKS = "navLinks";
    public static final String CACHE_VIDEO_CATALOG = "videoCatalog";

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(Arrays.asList(
                // 系统配置：10分钟，不常变化
                new CaffeineCache(CACHE_SYS_CONFIG, Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .recordStats()
                        .build()),

                // 用户认证信息：5分钟，高频访问
                new CaffeineCache(CACHE_AUTH_USER, Caffeine.newBuilder()
                        .maximumSize(5000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build()),

                // 知识库分享：5分钟
                new CaffeineCache(CACHE_KB_DOC_SHARE, Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build()),

                // 音乐搜索：60秒，高并发
                new CaffeineCache(CACHE_MUSIC_SEARCH, Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .recordStats()
                        .build()),

                // 音乐播放链接：20分钟，最高价值缓存
                new CaffeineCache(CACHE_MUSIC_PLAY, Caffeine.newBuilder()
                        .maximumSize(5000)
                        .expireAfterWrite(20, TimeUnit.MINUTES)
                        .recordStats()
                        .build()),

                // 音乐歌词：6小时，基本不变
                new CaffeineCache(CACHE_MUSIC_LYRIC, Caffeine.newBuilder()
                        .maximumSize(3000)
                        .expireAfterWrite(6, TimeUnit.HOURS)
                        .recordStats()
                        .build()),

                // 歌单列表：10分钟
                new CaffeineCache(CACHE_MUSIC_PLAYLIST, Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .recordStats()
                        .build()),

                // 榜单：10分钟
                new CaffeineCache(CACHE_MUSIC_TOPLIST, Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(10, TimeUnit.MINUTES)
                        .recordStats()
                        .build()),

                // 导航分类：5分钟，读多写少
                new CaffeineCache(CACHE_NAV_CATEGORIES, Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build()),

                // 导航链接：5分钟，读多写少
                new CaffeineCache(CACHE_NAV_LINKS, Caffeine.newBuilder()
                        .maximumSize(2000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build()),

                // 影视目录：5分钟
                new CaffeineCache(CACHE_VIDEO_CATALOG, Caffeine.newBuilder()
                        .maximumSize(1000)
                        .expireAfterWrite(5, TimeUnit.MINUTES)
                        .recordStats()
                        .build())
        ));
        return manager;
    }
}
