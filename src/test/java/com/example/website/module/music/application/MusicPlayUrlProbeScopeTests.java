package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import com.example.website.module.settings.application.SysConfigService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies which resolved play URLs are Range-probed before playback — the
 * server-side "probe before play" gate shared by the QQ/Netease/Kuwo plaintext
 * sources. Mirrors ios.25pan.com's pre-play Range check while guarding against
 * probing the local Qishui stream or SSRF targets.
 */
class MusicPlayUrlProbeScopeTests {

    private MusicPlaybackService service;

    @BeforeEach
    void setUp() {
        MusicCache cache = mock(MusicCache.class);
        SysConfigService config = mock(SysConfigService.class);
        QqMusicClient qq = mock(QqMusicClient.class);
        NeteaseMusicClient ne = mock(NeteaseMusicClient.class);
        KuwoMusicClient kw = mock(KuwoMusicClient.class);
        ChkszNeteaseMusicClient chksz = mock(ChkszNeteaseMusicClient.class);
        TuneFreePayClient pay = mock(TuneFreePayClient.class);
        QqTextFallbackClient fallback = mock(QqTextFallbackClient.class);
        PlayUrlVerifier verifier = mock(PlayUrlVerifier.class);
        QishuiMusicClient qs = mock(QishuiMusicClient.class);
        QishuiParseClient parser = mock(QishuiParseClient.class);
        MusicMetrics metrics = new MusicMetrics(new SimpleMeterRegistry());
        service = new MusicPlaybackService(cache, config, qq, ne, kw, chksz, pay, fallback,
                verifier, Runnable::run, 0L, metrics, qs, parser);
    }

    private PlayInfo info(MusicSource source, MusicSource actual, String url) {
        PlayInfo p = new PlayInfo();
        p.setSource(source);
        p.setActualSource(actual);
        p.setPlayUrl(url);
        p.setRequestedQuality(MusicQuality.K320.getValue());
        return p;
    }

    @Test
    void probesQqCdnDirectLink() {
        assertTrue(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.QQ, MusicSource.QQ, "https://wx.music.tc.qq.com/song.m4a?vkey=x")));
    }

    @Test
    void probesNeteaseCdnDirectLink() {
        assertTrue(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.NETEASE, MusicSource.NETEASE, "https://m801.music.126.net/2026/song.mp3")));
    }

    @Test
    void probesKuwoCdnDirectLink() {
        assertTrue(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.KUWO, MusicSource.KUWO, "https://www.kuwo.cn/api/v1/music/url/flac")));
    }

    @Test
    void usesActualSourceWhenPresent() {
        // A QQ request resolved by cross-source fallback to Netease still yields a
        // remote CDN URL that is safe to probe.
        assertTrue(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.QQ, MusicSource.NETEASE, "https://m801.music.126.net/song.mp3")));
    }

    @Test
    void neverProbesQishuiLocalStream() {
        assertFalse(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.QISHUI, MusicSource.QISHUI,
                        "https://751152.xyz/api/public/music/qishui/stream?ticket=abc")));
    }

    @Test
    void doesNotProbeSameOriginRelativeStream() {
        assertFalse(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.NETEASE, MusicSource.NETEASE,
                        "/api/public/music/qishui/stream?ticket=abc")));
    }

    @Test
    void rejectsUnknownCdnHost() {
        assertFalse(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.QQ, MusicSource.QQ, "https://evil.example.com/song.m4a")));
    }

    @Test
    void rejectsPrivateAndMetadataHostsToPreventSsfr() {
        String[] internal = {
                "http://127.0.0.1/song",
                "http://10.0.0.5/song",
                "http://192.168.1.1/song",
                "http://172.16.0.1/song",
                "http://169.254.169.254/latest/meta-data",
                "http://localhost/song",
        };
        for (String url : internal) {
            // Force one of the plaintext catalogs so only the SSRF guard can reject it.
            assertFalse(service.shouldVerifyRemotePlayUrl(
                    info(MusicSource.KUWO, MusicSource.KUWO, url)), "must reject " + url);
        }
    }

    @Test
    void rejectsNonHttpScheme() {
        assertFalse(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.QQ, MusicSource.QQ, "ftp://wx.music.tc.qq.com/song.m4a")));
    }

    @Test
    void toleratesNullAndEmpty() {
        assertFalse(service.shouldVerifyRemotePlayUrl(null));
        assertFalse(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.QQ, MusicSource.QQ, null)));
        assertFalse(service.shouldVerifyRemotePlayUrl(
                info(MusicSource.QQ, MusicSource.QQ, "   ")));
    }
}
