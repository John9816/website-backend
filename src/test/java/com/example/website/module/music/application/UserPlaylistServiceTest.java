package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.module.music.api.dto.MusicSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserPlaylistServiceTest {

    private final UserPlaylistService service = new UserPlaylistService(
            null, null, null, null, null, null, null, null);

    @Test
    void parsesQqDesktopPlaylistUrl() {
        UserPlaylistService.PlaylistRef ref = service.parseShareUrl(
                "https://y.qq.com/n/ryqq/playlist/7256933351");
        assertEquals(MusicSource.QQ, ref.source);
        assertEquals("7256933351", ref.externalId);
    }

    @Test
    void parsesQqMobileTaogeUrl() {
        UserPlaylistService.PlaylistRef ref = service.parseShareUrl(
                "https://i.y.qq.com/n2/m/share/details/taoge.html?hosteruin=&id=7256933351");
        assertEquals(MusicSource.QQ, ref.source);
        assertEquals("7256933351", ref.externalId);
    }

    @Test
    void parsesQqDisstidUrl() {
        UserPlaylistService.PlaylistRef ref = service.parseShareUrl(
                "https://y.qq.com/portal/playlist.html?disstid=12345678");
        assertEquals(MusicSource.QQ, ref.source);
        assertEquals("12345678", ref.externalId);
    }

    @Test
    void parsesNeteaseHashFragmentUrl() {
        UserPlaylistService.PlaylistRef ref = service.parseShareUrl(
                "https://music.163.com/#/playlist?id=2829896389");
        assertEquals(MusicSource.NETEASE, ref.source);
        assertEquals("2829896389", ref.externalId);
    }

    @Test
    void parsesNeteaseDesktopUrl() {
        UserPlaylistService.PlaylistRef ref = service.parseShareUrl(
                "https://music.163.com/playlist?id=2829896389&userid=1");
        assertEquals(MusicSource.NETEASE, ref.source);
        assertEquals("2829896389", ref.externalId);
    }

    @Test
    void parsesNeteaseMobileUrl() {
        UserPlaylistService.PlaylistRef ref = service.parseShareUrl(
                "https://music.163.com/m/playlist?id=2829896389");
        assertEquals(MusicSource.NETEASE, ref.source);
        assertEquals("2829896389", ref.externalId);
    }

    @Test
    void parsesUrlEmbeddedInShareText() {
        UserPlaylistService.PlaylistRef ref = service.parseShareUrl(
                "分享一个歌单 https://y.qq.com/n/ryqq/playlist/7256933351 喜欢就听听吧");
        assertEquals(MusicSource.QQ, ref.source);
        assertEquals("7256933351", ref.externalId);
    }

    @Test
    void routesOfficialQishuiSharesWithoutLegacyRedirects() {
        QishuiMusicClient client = org.mockito.Mockito.mock(QishuiMusicClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "qishui", client);
        String url = "https://music.douyin.com/qishui/share/playlist?playlist_id=123456";
        org.mockito.Mockito.when(client.resolveShareUrl(url)).thenReturn("share:123456");
        UserPlaylistService.PlaylistRef ref = service.parseShareUrl(url);
        assertEquals(MusicSource.QISHUI, ref.source);
        assertEquals("share:123456", ref.externalId);
    }

    @Test
    void rejectsUnrelatedUrl() {
        assertThrows(MusicBusinessException.class,
                () -> service.parseShareUrl("https://example.com/playlist?id=1"));
    }

    @Test
    void rejectsGarbageInput() {
        assertThrows(MusicBusinessException.class,
                () -> service.parseShareUrl("not a url at all"));
    }
}
