package com.example.website.module.navigation.application;

import com.example.website.module.navigation.api.dto.CategoryView;
import com.example.website.module.navigation.api.dto.LinkView;
import com.example.website.config.PerformanceMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.stream.Collectors;
import com.example.website.shared.transaction.AfterCommit;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

/** Local cache for the small, frequently-read navigation surface. */
@Service
public class NavigationCacheService {

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

    public List<CategoryView> categories(Long userId) {
        List<CategoryView> value = copyCategories(categories.getIfPresent(userId));
        if (value == null) metrics.recordCacheMiss("navigation.categories");
        else metrics.recordCacheHit("navigation.categories");
        return value;
    }

    public void putCategories(Long userId, List<CategoryView> value) {
        categories.put(userId, copyCategories(value));
    }

    public List<LinkView> links(Long userId) {
        List<LinkView> value = copyLinks(links.getIfPresent(userId));
        if (value == null) metrics.recordCacheMiss("navigation.links");
        else metrics.recordCacheHit("navigation.links");
        return value;
    }

    public void putLinks(Long userId, List<LinkView> value) {
        links.put(userId, copyLinks(value));
    }

    public List<CategoryView> navigation(Long userId) {
        List<CategoryView> value = copyCategories(navigation.getIfPresent(userId));
        if (value == null) metrics.recordCacheMiss("navigation.aggregate");
        else metrics.recordCacheHit("navigation.aggregate");
        return value;
    }

    public void putNavigation(Long userId, List<CategoryView> value) {
        navigation.put(userId, copyCategories(value));
    }

    public void invalidateUser(Long userId) {
        if (userId == null) return;
        AfterCommit.run(() -> {
            categories.invalidate(userId);
            links.invalidate(userId);
            navigation.invalidate(userId);
        });
    }

    public void invalidateAll() {
        AfterCommit.run(() -> {
            categories.invalidateAll();
            links.invalidateAll();
            navigation.invalidateAll();
        });
    }

    private static <T> Cache<Long, List<T>> cache() {
        return Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
    }

    public List<CategoryView> categories(Long userId, Supplier<List<CategoryView>> loader) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return loader.get();
        List<CategoryView> cached = categories(userId);
        return cached != null ? cached : copyCategories(categories.get(userId, key -> copyCategories(loader.get())));
    }

    public List<LinkView> links(Long userId, Supplier<List<LinkView>> loader) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return loader.get();
        List<LinkView> cached = links(userId);
        return cached != null ? cached : copyLinks(links.get(userId, key -> copyLinks(loader.get())));
    }

    public List<CategoryView> navigation(Long userId, Supplier<List<CategoryView>> loader) {
        // Transactional readers may see uncommitted data or an older repeatable-read snapshot.
        // Neither is eligible for the shared cache.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return loader.get();
        List<CategoryView> cached = navigation(userId);
        return cached != null ? cached : copyCategories(navigation.get(userId, key -> copyCategories(loader.get())));
    }

    private static List<CategoryView> copyCategories(List<CategoryView> value) {
        if (value == null) return null;
        return value.stream().map(c -> {
            CategoryView copy = new CategoryView();
            copy.setId(c.getId()); copy.setName(c.getName()); copy.setIcon(c.getIcon());
            copy.setSortOrder(c.getSortOrder()); copy.setCreatedAt(c.getCreatedAt());
            copy.setUpdatedAt(c.getUpdatedAt()); copy.setLinks(copyLinks(c.getLinks()));
            return copy;
        }).collect(Collectors.toList());
    }

    private static List<LinkView> copyLinks(List<LinkView> value) {
        if (value == null) return null;
        return value.stream().map(l -> {
            LinkView copy = new LinkView();
            copy.setId(l.getId()); copy.setCategoryId(l.getCategoryId()); copy.setName(l.getName());
            copy.setUrl(l.getUrl()); copy.setDescription(l.getDescription()); copy.setIcon(l.getIcon());
            copy.setSortOrder(l.getSortOrder()); copy.setCreatedAt(l.getCreatedAt()); copy.setUpdatedAt(l.getUpdatedAt());
            return copy;
        }).collect(Collectors.toList());
    }
}
