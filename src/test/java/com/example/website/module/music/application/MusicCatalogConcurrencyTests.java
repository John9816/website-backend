package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.PlaylistListView;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.settings.application.SysConfigService;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicCatalogConcurrencyTests {
    @Test
    void twelveSimultaneousColdRequestsPerformOneUpstreamLoad() throws Exception {
        int callers = 12;
        CyclicBarrier misses = new CyclicBarrier(callers);
        MusicCache cache = new MusicCache() {
            final ThreadLocal<Boolean> first = ThreadLocal.withInitial(() -> true);
            @Override public <T> T get(String key, Class<T> type) {
                T value = super.get(key, type);
                if (first.get()) {
                    first.set(false);
                    try { misses.await(5, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new AssertionError(e); }
                }
                return value;
            }
        };
        QqMusicClient qq = mock(QqMusicClient.class);
        PlaylistListView expected = new PlaylistListView();
        when(qq.fetchPlaylists(null, "hot", 1, 20)).thenReturn(expected);
        MusicService service = MusicTestServices.create(cache, mock(SysConfigService.class), qq,
                mock(NeteaseMusicClient.class), mock(KuwoMusicClient.class),
                mock(ChkszNeteaseMusicClient.class), mock(TuneFreePayClient.class),
                mock(QqTextFallbackClient.class), mock(PlayUrlVerifier.class));
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<PlaylistListView>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) results.add(pool.submit(() -> service.playlists("qq", null, null, 1, 20)));
            for (Future<PlaylistListView> f : results) assertSame(expected, f.get(10, TimeUnit.SECONDS));
            verify(qq, times(1)).fetchPlaylists(null, "hot", 1, 20);
        } finally { pool.shutdownNow(); }
    }
}
