package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.AlbumDetailView;
import com.example.website.module.music.api.dto.LyricInfo;
import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.MusicSearchType;
import com.example.website.module.music.api.dto.SearchCollectionItem;
import com.example.website.module.music.api.dto.SearchResultView;
import com.example.website.module.music.api.dto.SongSearchItem;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.website.module.settings.application.SysConfigService;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

class QishuiRoutingTests {
    @Test
    void playlistSearchPreservesUnknownTotalAndCachesTheResult() {
        QishuiMusicClient qs = mock(QishuiMusicClient.class);
        MusicSearchService service = new MusicSearchService(
                new MusicCache(), mock(QqMusicClient.class),
                mock(NeteaseMusicClient.class), mock(KuwoMusicClient.class), qs,
                new MusicMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        List<SearchCollectionItem> items = Collections.singletonList(new SearchCollectionItem());
        when(qs.searchCollections("test", MusicSearchType.PLAYLIST, 1, 10))
                .thenReturn(new NeteaseMusicClient.CollectionSearchResult(null, items));

        SearchResultView result = service.search("qs", "test", "playlist", 1, 10);
        assertNull(result.getTotal());
        assertEquals(items, result.getPlaylists());
        assertEquals(MusicSource.QISHUI, result.getSource());
        assertSame(result, service.search("qishui", "test", "playlist", 1, 10));
        verify(qs, times(1)).searchCollections("test", MusicSearchType.PLAYLIST, 1, 10);
    }

    @Test
    void existingEndpointsRouteQsToIndependentClientAndNeverUseLegacyParser() {
        QishuiMusicClient qs = mock(QishuiMusicClient.class);
        QishuiParseClient parser = mock(QishuiParseClient.class);
        QqMusicClient qq = mock(QqMusicClient.class);
        NeteaseMusicClient wy = mock(NeteaseMusicClient.class);
        KuwoMusicClient kw = mock(KuwoMusicClient.class);
        TuneFreePayClient legacy = mock(TuneFreePayClient.class);
        SysConfigService config = mock(SysConfigService.class);
        when(config.getValue(anyString())).thenReturn(Optional.empty());
        MusicService service =
                MusicTestServices.create(
                        new MusicCache(),
                        config,
                        qq,
                        wy,
                        kw,
                        mock(ChkszNeteaseMusicClient.class),
                        legacy,
                        mock(QqTextFallbackClient.class),
                        mock(PlayUrlVerifier.class), Runnable::run, 0L, qs, parser);
        when(qs.search("test", 1, 10)).thenReturn(Collections.singletonList(new SongSearchItem()));
        assertEquals(MusicSource.QISHUI, service.search("qs", "test", 1, 10).getSource());
        when(parser.play("track", MusicQuality.K320))
                .thenThrow(new MusicBusinessException(MusicErrorCode.UNSUPPORTED_OPERATION));
        assertEquals(
                MusicErrorCode.UNSUPPORTED_OPERATION,
                assertThrows(MusicBusinessException.class, () -> service.play("qs", "track", null))
                        .getErrorCode());
        verifyNoInteractions(legacy, qq, wy, kw);
        AlbumDetailView album = new AlbumDetailView();
        when(qs.fetchAlbumDetail("album", 1, 10)).thenReturn(album);
        assertSame(album, service.albumDetail("qs", "album", 1, 10));
        verify(qs).fetchAlbumDetail("album", 1, 10);
    }

    @Test
    void lyricFallbackDoesNotSendQishuiIdToLegacyProvider() {
        QishuiMusicClient qs = mock(QishuiMusicClient.class);
        TuneFreePayClient legacy = mock(TuneFreePayClient.class);
        MusicLyricService service =
                new MusicLyricService(
                        new MusicCache(),
                        mock(QqMusicClient.class),
                        mock(NeteaseMusicClient.class),
                        mock(KuwoMusicClient.class),
                        legacy,
                        mock(QqTextFallbackClient.class),
                        new MusicMetrics(
                                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "qishui", qs);
        when(qs.fetchLyrics("id")).thenReturn(new LyricInfo("[00:01.000]测试", null));
        assertEquals("qishui", service.lyric("qs", "id").getProvider());
        verifyNoInteractions(legacy);
    }
}
