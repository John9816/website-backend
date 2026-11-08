package com.example.website.module.navigation.application;

import com.example.website.module.navigation.api.dto.CategoryView;
import com.example.website.module.navigation.api.dto.LinkView;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class NavigationCacheServiceTests {

    @Test
    void cachesAdminIdAndClearsItWhenInvalidated() {
        com.example.website.module.identity.application.DefaultAdminCache cache =
                new com.example.website.module.identity.application.DefaultAdminCache(new com.example.website.config.PerformanceMetrics());
        int[] loads = {0};

        assertEquals(7L, cache.adminId(() -> {
            loads[0]++;
            return 7L;
        }));
        assertEquals(7L, cache.adminId(() -> {
            loads[0]++;
            return 8L;
        }));
        assertEquals(1, loads[0]);

        cache.invalidateAdmin();
        assertEquals(8L, cache.adminId(() -> 8L));
    }

    @Test
    void copiesCachedListsAndInvalidatesAllNavigationViewsForUser() {
        NavigationCacheService cache = new NavigationCacheService();
        CategoryView category = new CategoryView();
        category.setId(1L);
        LinkView link = new LinkView();
        link.setId(2L);

        cache.putCategories(9L, Collections.singletonList(category));
        cache.putLinks(9L, Collections.singletonList(link));
        cache.putNavigation(9L, Collections.singletonList(category));

        assertNotSame(cache.categories(9L), cache.categories(9L));
        assertEquals(1, cache.links(9L).size());
        assertEquals(1, cache.navigation(9L).size());

        cache.invalidateUser(9L);
        assertEquals(null, cache.categories(9L));
        assertEquals(null, cache.links(9L));
        assertEquals(null, cache.navigation(9L));
    }
    @Test
    void cachedNavigationDoesNotShareMutableDtosWithCallers() {
        NavigationCacheService cache = new NavigationCacheService();
        CategoryView category = new CategoryView();
        category.setName("original");
        LinkView link = new LinkView();
        link.setName("original link");
        category.setLinks(Collections.singletonList(link));
        cache.putNavigation(9L, Collections.singletonList(category));
        category.setName("changed input");
        cache.navigation(9L).get(0).getLinks().get(0).setName("changed output");
        assertEquals("original", cache.navigation(9L).get(0).getName());
        assertEquals("original link", cache.navigation(9L).get(0).getLinks().get(0).getName());
    }

    @Test
    void invalidationOccursAfterTransactionCommit() {
        NavigationCacheService cache = new NavigationCacheService();
        cache.putCategories(9L, Collections.singletonList(new CategoryView()));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            cache.invalidateUser(9L);
            // Readers outside this transaction may keep seeing the committed value until commit.
            org.junit.jupiter.api.Assertions.assertNotNull(cache.categories(9L));
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCommit());
            org.junit.jupiter.api.Assertions.assertNull(cache.categories(9L));
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
    }
}
