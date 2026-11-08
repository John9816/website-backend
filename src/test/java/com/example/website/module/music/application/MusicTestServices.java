package com.example.website.module.music.application;

import com.example.website.module.settings.application.SysConfigService;
import java.util.concurrent.Executor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.mockito.Mockito.mock;

final class MusicTestServices {
    static MusicService create(MusicCache cache, SysConfigService config, QqMusicClient qq,
            NeteaseMusicClient ne, KuwoMusicClient kw, ChkszNeteaseMusicClient chksz,
            TuneFreePayClient pay, QqTextFallbackClient fallback, PlayUrlVerifier verifier) {
        return create(cache, config, qq, ne, kw, chksz, pay, fallback, verifier, Runnable::run, 0L);
    }
    static MusicService create(MusicCache cache, SysConfigService config, QqMusicClient qq,
            NeteaseMusicClient ne, KuwoMusicClient kw, ChkszNeteaseMusicClient chksz,
            TuneFreePayClient pay, QqTextFallbackClient fallback, PlayUrlVerifier verifier,
            Executor executor, long hedgeDelay) {
        return create(cache, config, qq, ne, kw, chksz, pay, fallback, verifier, executor, hedgeDelay,
                mock(QishuiMusicClient.class), mock(QishuiParseClient.class));
    }
    static MusicService create(MusicCache cache, SysConfigService config, QqMusicClient qq,
            NeteaseMusicClient ne, KuwoMusicClient kw, ChkszNeteaseMusicClient chksz,
            TuneFreePayClient pay, QqTextFallbackClient fallback, PlayUrlVerifier verifier,
            Executor executor, long hedgeDelay, QishuiMusicClient qs, QishuiParseClient parser) {
        MusicMetrics metrics = new MusicMetrics(new SimpleMeterRegistry());
        MusicLyricService lyrics = new MusicLyricService(cache, qq, ne, kw, pay, fallback, metrics);
        org.springframework.test.util.ReflectionTestUtils.setField(lyrics, "qishui", qs);
        return new MusicService(new MusicSearchService(cache, qq, ne, kw, qs, metrics),
                new MusicCatalogService(cache, qq, ne, kw, qs),
                new MusicPlaybackService(cache, config, qq, ne, kw, chksz, pay, fallback, verifier,
                        executor, hedgeDelay, metrics, qs, parser), lyrics);
    }
}
