package com.example.website.service;

import com.example.website.dto.CategoryView;
import com.example.website.dto.LinkView;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class NavigationCacheServiceTests {

    @Test
    void cachesAdminIdAndClearsItWhenInvalidated() {
        NavigationCacheService cache = new NavigationCacheService();
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
}
