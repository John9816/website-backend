package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.PlaylistListView;
import com.example.website.config.PerformanceMetrics;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicCatalogCacheTests {
    @Test
    void failuresAreNotCachedAndRetryKeepsTheOriginalError() {
        QqMusicClient qq = mock(QqMusicClient.class);
        MusicCatalogService catalog = new MusicCatalogService(new MusicCache(), qq, null, null, null);
        MusicBusinessException failure = new MusicBusinessException(MusicErrorCode.UPSTREAM_TIMEOUT);
        PlaylistListView recovered = new PlaylistListView();
        when(qq.fetchPlaylists(null, "hot", 1, 20)).thenThrow(failure).thenReturn(recovered);
        assertSame(failure, assertThrows(MusicBusinessException.class, () -> catalog.playlists("qq", null, null, 1, 20)));
        assertSame(recovered, catalog.playlists("qq", null, null, 1, 20));
        assertSame(recovered, catalog.playlists("qq", null, null, 1, 20));
        verify(qq, times(2)).fetchPlaylists(null, "hot", 1, 20);
    }

    @Test
    void playlistTtlAndNormalizedPaginationRemainUnchanged() {
        AtomicLong now = new AtomicLong(1000);
        MusicCache cache = new MusicCache(new PerformanceMetrics(), now::get);
        QqMusicClient qq = mock(QqMusicClient.class);
        MusicCatalogService catalog = new MusicCatalogService(cache, qq, null, null, null);
        when(qq.fetchPlaylists("rock", "new", 1, 50)).thenReturn(new PlaylistListView());
        catalog.playlists("qq", " rock ", " new ", 0, 200);
        now.addAndGet(30 * 60 * 1000);
        catalog.playlists("qq", "rock", "new", 1, 50);
        verify(qq, times(1)).fetchPlaylists("rock", "new", 1, 50);
        now.incrementAndGet();
        catalog.playlists("qq", "rock", "new", 1, 50);
        verify(qq, times(2)).fetchPlaylists("rock", "new", 1, 50);
    }

    @Test
    void differentKeysDoNotSerializeBehindOneSlowProviderCall() throws Exception {
        QqMusicClient qq = mock(QqMusicClient.class);
        MusicCatalogService catalog = new MusicCatalogService(new MusicCache(), qq, null, null, null);
        CountDownLatch slowStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        when(qq.fetchPlaylists("slow", "hot", 1, 20)).thenAnswer(invocation -> {
            slowStarted.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return new PlaylistListView();
        });
        when(qq.fetchPlaylists("fast", "hot", 1, 20)).thenReturn(new PlaylistListView());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> slow = pool.submit(() -> catalog.playlists("qq", "slow", null, 1, 20));
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
            Future<?> fast = pool.submit(() -> catalog.playlists("qq", "fast", null, 1, 20));
            assertNotNull(fast.get(2, TimeUnit.SECONDS));
            assertFalse(slow.isDone());
            release.countDown(); slow.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
