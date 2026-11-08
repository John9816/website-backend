package com.example.website.module.navigation.application;

import com.example.website.module.navigation.api.dto.CategoryView;
import com.example.website.module.navigation.domain.Category;
import com.example.website.module.navigation.infrastructure.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NavigationConcurrencyTests {
    @Test
    void twelveColdNavigationRequestsUseOnePairOfBatchedQueries() throws Exception {
        CyclicBarrier misses = new CyclicBarrier(12);
        NavigationCacheService cache = new NavigationCacheService() {
            @Override public List<CategoryView> navigation(Long userId) {
                List<CategoryView> value = super.navigation(userId);
                try { misses.await(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
                return value;
            }
        };
        CategoryRepository categories = mock(CategoryRepository.class);
        NavLinkRepository links = mock(NavLinkRepository.class);
        Category category = new Category(); category.setId(1L);
        when(categories.findByUserIdOrderBySortOrderAscIdAsc(7L)).thenReturn(Collections.singletonList(category));
        when(links.findByUserIdOrderBySortOrderAscIdAsc(7L)).thenReturn(Collections.emptyList());
        CategoryService service = new CategoryService(categories, links, cache);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<List<CategoryView>>> calls = new ArrayList<>();
            for (int i=0; i<12; i++) calls.add(pool.submit(() -> service.listAllWithLinks(7L)));
            for (Future<List<CategoryView>> call : calls) assertEquals(1, call.get(10, TimeUnit.SECONDS).size());
            verify(categories, times(1)).findByUserIdOrderBySortOrderAscIdAsc(7L);
            verify(links, times(1)).findByUserIdOrderBySortOrderAscIdAsc(7L);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void transactionalReadsNeverPublishUncommittedDataAndRollbackKeepsCommittedCache() {
        NavigationCacheService cache = new NavigationCacheService();
        CategoryView committed = new CategoryView(); committed.setName("committed");
        cache.putCategories(7L, Collections.singletonList(committed));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            CategoryView dirty = new CategoryView(); dirty.setName("uncommitted");
            assertEquals("uncommitted", cache.categories(7L, () -> Collections.singletonList(dirty)).get(0).getName());
            cache.invalidateUser(7L);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
        } finally { TransactionSynchronizationManager.clear(); }
        assertEquals("committed", cache.categories(7L, Collections::emptyList).get(0).getName());
    }

    @Test
    void invalidationCannotBeOverwrittenByAnOlderLoadAndUsersRemainIsolated() throws Exception {
        NavigationCacheService cache = new NavigationCacheService();
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> load = pool.submit(() -> cache.categories(7L, () -> {
                loading.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                CategoryView old = new CategoryView(); old.setName("old");
                return Collections.singletonList(old);
            }));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            CountDownLatch invalidating = new CountDownLatch(1);
            Future<?> eviction = pool.submit(() -> { invalidating.countDown(); cache.invalidateUser(7L); });
            assertTrue(invalidating.await(5, TimeUnit.SECONDS));
            release.countDown(); load.get(5, TimeUnit.SECONDS); eviction.get(5, TimeUnit.SECONDS);
            CategoryView fresh = new CategoryView(); fresh.setName("fresh");
            assertEquals("fresh", cache.categories(7L, () -> Collections.singletonList(fresh)).get(0).getName());
            assertTrue(cache.categories(8L, Collections::emptyList).isEmpty());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
