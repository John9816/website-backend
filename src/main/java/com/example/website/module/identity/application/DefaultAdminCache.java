package com.example.website.module.identity.application;

import com.example.website.config.PerformanceMetrics;
import com.example.website.shared.transaction.AfterCommit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class DefaultAdminCache {
    private static final String ADMIN_KEY = "admin";
    private final Cache<String, Long> adminIds = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build();
    private final PerformanceMetrics metrics;

    public Long adminId(Supplier<Long> loader) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return loader.get();
        Long cached = adminIds.getIfPresent(ADMIN_KEY);
        if (cached != null) { metrics.recordCacheHit("navigation.admin"); return cached; }
        metrics.recordCacheMiss("navigation.admin");
        return adminIds.get(ADMIN_KEY, key -> loader.get());
    }

    public void invalidateAdmin() {
        AfterCommit.run(() -> adminIds.invalidate(ADMIN_KEY));
    }

}
