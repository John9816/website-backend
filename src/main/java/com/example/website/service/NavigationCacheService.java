package com.example.website.service;

import com.example.website.dto.CategoryView;
import com.example.website.dto.LinkView;
import com.example.website.config.PerformanceMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

/** Local cache for the small, frequently-read navigation surface. */
@Service
public class NavigationCacheService {

    private static final String ADMIN_KEY = "admin";
    private final Cache<String, Long> adminIds = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();
    private final Cache<Long, List<CategoryView>> categories = cache();
    private final Cache<Long, List<LinkView>> links = cache();
    private final Cache<Long, List<CategoryView>> navigation = cache();
    private final PerformanceMetrics metrics;

    public NavigationCacheService() {
        this(new PerformanceMetrics());
    }

    @Autowired
    public NavigationCacheService(PerformanceMetrics metrics) {
        this.metrics = metrics;
    }

    public Long adminId(Supplier<Long> loader) {
        Long cached = adminIds.getIfPresent(ADMIN_KEY);
        if (cached != null) { metrics.recordCacheHit("navigation.admin"); return cached; }
        metrics.recordCacheMiss("navigation.admin");
        Long value = loader.get();
        if (value != null) adminIds.put(ADMIN_KEY, value);
        return value;
    }

    public void invalidateAdmin() {
        adminIds.invalidate(ADMIN_KEY);
    }

    public List<CategoryView> categories(Long userId) {
        List<CategoryView> value = copy(categories.getIfPresent(userId));
        if (value == null) metrics.recordCacheMiss("navigation.categories");
        else metrics.recordCacheHit("navigation.categories");
        return value;
    }

    public void putCategories(Long userId, List<CategoryView> value) {
        categories.put(userId, copy(value));
    }

    public List<LinkView> links(Long userId) {
        List<LinkView> value = copy(links.getIfPresent(userId));
        if (value == null) metrics.recordCacheMiss("navigation.links");
        else metrics.recordCacheHit("navigation.links");
        return value;
    }

    public void putLinks(Long userId, List<LinkView> value) {
        links.put(userId, copy(value));
    }

    public List<CategoryView> navigation(Long userId) {
        List<CategoryView> value = copy(navigation.getIfPresent(userId));
        if (value == null) metrics.recordCacheMiss("navigation.aggregate");
        else metrics.recordCacheHit("navigation.aggregate");
        return value;
    }

    public void putNavigation(Long userId, List<CategoryView> value) {
        navigation.put(userId, copy(value));
    }

    public void invalidateUser(Long userId) {
        if (userId == null) return;
        categories.invalidate(userId);
        links.invalidate(userId);
        navigation.invalidate(userId);
    }

    public void invalidateAll() {
        categories.invalidateAll();
        links.invalidateAll();
        navigation.invalidateAll();
    }

    private static <T> Cache<Long, List<T>> cache() {
        return Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
    }

    private static <T> List<T> copy(List<T> value) {
        return value == null ? null : new ArrayList<>(value);
    }
}
