package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.dto.music.LyricInfo;
import com.example.website.dto.music.AlbumDetailView;
import com.example.website.dto.music.ArtistWorksView;
import com.example.website.dto.music.LyricView;
import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.NeteasePlaybackQuality;
import com.example.website.dto.music.PlayInfo;
import com.example.website.dto.music.PlaylistListView;
import com.example.website.dto.music.SongSearchItem;
import com.example.website.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MusicServiceTests {

    @Mock
    private QqMusicClient qq;

    @Mock
    private NeteaseMusicClient netease;

    @Mock
    private ChkszNeteaseMusicClient chkszNetease;

    @Mock
    private KuwoMusicClient kuwo;

    @Mock
    private TuneFreePayClient tfPay;

    @Mock
    private QqTextFallbackClient qqTextFallback;

    @Mock
    private PlayUrlVerifier playUrlVerifier;

    @Mock
    private SysConfigService configService;

    private MusicService musicService;

    @BeforeEach
    void setUp() {
        lenient().when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER)).thenReturn(Optional.empty());
        lenient().when(configService.getValue(MusicService.CFG_CROSS_SOURCE_ORDER)).thenReturn(Optional.empty());
        lenient().when(qq.resolvePlay(anyString(), any(MusicQuality.class)))
                .thenThrow(new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL, "resolver unavailable"));
        musicService = new MusicService(new MusicCache(), configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier);
    }

    @Test
    void lyricUsesOfficialQqEndpointEvenWhenPlayCacheContainsFallbackLyric() {
        SongSearchItem meta = song("song-mid", "track-one", "artist-one");
        PlayInfo fallback = new PlayInfo();
        fallback.setId("song-mid");
        fallback.setSource(MusicSource.QQ);
        fallback.setActualSource(MusicSource.QQ);
        fallback.setName("track-one");
        fallback.setArtist("artist-one");
        fallback.setAlbum("album-one");
        fallback.setPlayUrl("https://example.com/fallback.m4a");
        fallback.setActualQuality("320k");
        fallback.setLyric(new LyricInfo("[00:00.00]line-one", null));

        when(tfPay.play(MusicSource.QQ, "song-mid", MusicQuality.K128))
                .thenThrow(new MusicBusinessException(MusicErrorCode.MISSING_UPSTREAM_TOKEN, "token missing"));
        when(qq.fetchSongInfo("song-mid")).thenReturn(meta);
        when(qqTextFallback.fetchSongUrl("song-mid", MusicQuality.K128, meta)).thenReturn(fallback);
        when(qq.fetchLyric("song-mid")).thenReturn("[00:00.00]official-line");

        PlayInfo play = musicService.play("qq", "song-mid", "128k");

        assertEquals("song-mid", play.getId());
        assertEquals(MusicSource.QQ, play.getSource());
        assertEquals(MusicSource.QQ, play.getActualSource());
        assertEquals("https://example.com/fallback.m4a", play.getPlayUrl());
        assertEquals("128k", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
        assertEquals("[00:00.00]line-one", play.getLyric().getLineLyrics());

        LyricView lyric = musicService.lyric("qq", "song-mid");
        assertEquals("[00:00.00]official-line", lyric.getLineLyrics());
        assertNull(lyric.getKaraokeLyrics());
        assertEquals("qq", lyric.getProvider());
        assertTrue(lyric.getOfficial());
        verify(qq).fetchLyric("song-mid");
        verify(qqTextFallback, never()).fetchLyric(meta);
    }

    @Test
    void lyricFallsBackToQqTextLookupWhenOfficialLyricFails() {
        SongSearchItem meta = song("song-mid", "track-two", "artist-two");

        when(qq.fetchLyric("song-mid"))
                .thenThrow(new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED, "qq lyric failed"));
        when(qq.fetchSongInfo("song-mid")).thenReturn(meta);
        when(qqTextFallback.fetchLyric(meta)).thenReturn(new LyricInfo("[00:01.00]line-two", null));

        LyricView lyric = musicService.lyric("qq", "song-mid");

        assertEquals("song-mid", lyric.getId());
        assertEquals(MusicSource.QQ, lyric.getSource());
        assertEquals("[00:01.00]line-two", lyric.getLineLyrics());
        assertNull(lyric.getKaraokeLyrics());
        assertEquals("cyapi", lyric.getProvider());
        assertFalse(lyric.getOfficial());
    }

    @Test
    void kuwoLyricUsesOfficialEndpointBeforeTuneFreeFallback() {
        when(kuwo.fetchLyric("631473518")).thenReturn("[00:00.00]official-kuwo");

        LyricView lyric = musicService.lyric("kuwo", "631473518");

        assertEquals("[00:00.00]official-kuwo", lyric.getLineLyrics());
        assertEquals("kuwo", lyric.getProvider());
        assertTrue(lyric.getOfficial());
        verify(tfPay, never()).play(MusicSource.KUWO, "631473518", MusicQuality.FLAC);
    }

    @Test
    void kuwoLyricFallsBackToTuneFreeWhenOfficialEndpointFails() {
        PlayInfo fallback = new PlayInfo();
        fallback.setLyric(new LyricInfo("[00:00.00]fallback-kuwo", null));
        when(kuwo.fetchLyric("631473518"))
                .thenThrow(new MusicBusinessException(MusicErrorCode.UPSTREAM_LYRIC_FAILED, "kuwo failed"));
        when(tfPay.play(MusicSource.KUWO, "631473518", MusicQuality.FLAC)).thenReturn(fallback);

        LyricView lyric = musicService.lyric("kuwo", "631473518");

        assertEquals("[00:00.00]fallback-kuwo", lyric.getLineLyrics());
        assertEquals("tunefree", lyric.getProvider());
        assertFalse(lyric.getOfficial());
    }

    @Test
    void playFallsBackToQqTextResolverWhenPrimaryQqUrlFailsProbe() {
        SongSearchItem meta = song("song-mid", "track-three", "artist-three");

        PlayInfo primary = new PlayInfo();
        primary.setId("song-mid");
        primary.setSource(MusicSource.QQ);
        primary.setActualSource(MusicSource.QQ);
        primary.setName("track-three");
        primary.setArtist("artist-three");
        primary.setPlayUrl("http://wx.music.tc.qq.com/M500songmid.mp3?vkey=bad");
        primary.setActualQuality("128k");

        PlayInfo fallback = new PlayInfo();
        fallback.setId("song-mid");
        fallback.setSource(MusicSource.QQ);
        fallback.setActualSource(MusicSource.QQ);
        fallback.setName("track-three");
        fallback.setArtist("artist-three");
        fallback.setPlayUrl("https://cdn.cyapi.top/fallback-song.mid");
        fallback.setActualQuality("320k");

        when(tfPay.play(MusicSource.QQ, "song-mid", MusicQuality.K128)).thenReturn(primary);
        when(playUrlVerifier.isPlayable(primary.getPlayUrl())).thenReturn(false);
        when(qq.fetchSongInfo("song-mid")).thenReturn(meta);
        when(qqTextFallback.fetchSongUrl("song-mid", MusicQuality.K128, meta)).thenReturn(fallback);

        PlayInfo play = musicService.play("qq", "song-mid", "128k");

        assertEquals("https://cdn.cyapi.top/fallback-song.mid", play.getPlayUrl());
        assertEquals("128k", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
        verify(chkszNetease, never()).resolve(anyString(), any(NeteasePlaybackQuality.class));
    }

    @Test
    void qqDefaultsToXunhuisiHighQualityBeforeTuneFree() {
        PlayInfo primary = new PlayInfo();
        primary.setId("song-mid");
        primary.setSource(MusicSource.QQ);
        primary.setActualSource(MusicSource.QQ);
        primary.setPlayUrl("https://isure6.stream.qqmusic.qq.com/C600song-mid.m4a?vkey=test");
        primary.setActualQuality("320k");
        doReturn(primary).when(qq).resolvePlay("song-mid", MusicQuality.K320);
        when(playUrlVerifier.isPlayable(primary.getPlayUrl())).thenReturn(true);

        PlayInfo play = musicService.play("qq", "song-mid", null);

        assertEquals(primary.getPlayUrl(), play.getPlayUrl());
        assertEquals("320k", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
        verify(tfPay, never()).play(any(MusicSource.class), anyString(), any(MusicQuality.class));
        verify(qq).resolvePlay("song-mid", MusicQuality.K320);
    }

    @Test
    void neteaseUsesChkszBeforeTuneFreeForJymaster() {
        PlayInfo preferred = new PlayInfo();
        preferred.setId("2034742057");
        preferred.setSource(MusicSource.NETEASE);
        preferred.setActualSource(MusicSource.NETEASE);
        preferred.setPlayUrl("https://cdn.example.com/master.flac");
        preferred.setActualQuality("jymaster");
        when(chkszNetease.resolve("2034742057", NeteasePlaybackQuality.JYMASTER)).thenReturn(preferred);

        PlayInfo play = musicService.play("netease", "2034742057", "jymaster");

        assertEquals("https://cdn.example.com/master.flac", play.getPlayUrl());
        assertEquals("jymaster", play.getRequestedQuality());
        assertEquals("jymaster", play.getActualQuality());
        verify(tfPay, never()).play(any(MusicSource.class), anyString(), any(MusicQuality.class));
    }

    @Test
    void neteaseFallsBackToMappedTuneFreeQualityWhenChkszFails() {
        when(chkszNetease.resolve("2034742057", NeteasePlaybackQuality.HIRES))
                .thenThrow(new MusicBusinessException(MusicErrorCode.UPSTREAM_PLAY_FAILED, "chksz unavailable"));
        PlayInfo fallback = new PlayInfo();
        fallback.setId("2034742057");
        fallback.setSource(MusicSource.NETEASE);
        fallback.setActualSource(MusicSource.NETEASE);
        fallback.setPlayUrl("https://cdn.example.com/fallback.flac");
        fallback.setActualQuality("flac24bit");
        when(tfPay.play(MusicSource.NETEASE, "2034742057", MusicQuality.FLAC24)).thenReturn(fallback);

        PlayInfo play = musicService.play("netease", "2034742057", "hires");

        assertEquals("https://cdn.example.com/fallback.flac", play.getPlayUrl());
        assertEquals("hires", play.getRequestedQuality());
        assertEquals("flac24bit", play.getActualQuality());
        verify(tfPay).play(MusicSource.NETEASE, "2034742057", MusicQuality.FLAC24);
    }

    @Test
    void neteaseLegacyAliasSharesCanonicalCacheAndPreservesRequestedValue() {
        PlayInfo preferred = new PlayInfo();
        preferred.setId("2034742057");
        preferred.setSource(MusicSource.NETEASE);
        preferred.setActualSource(MusicSource.NETEASE);
        preferred.setPlayUrl("https://cdn.example.com/lossless.flac");
        preferred.setActualQuality("lossless");
        when(chkszNetease.resolve("2034742057", NeteasePlaybackQuality.LOSSLESS)).thenReturn(preferred);

        PlayInfo canonical = musicService.play("netease", "2034742057", "lossless");
        PlayInfo legacy = musicService.play("netease", "2034742057", "flac");

        assertEquals("lossless", canonical.getRequestedQuality());
        assertEquals("flac", legacy.getRequestedQuality());
        assertTrue(Boolean.TRUE.equals(legacy.getFromCache()));
        verify(chkszNetease).resolve("2034742057", NeteasePlaybackQuality.LOSSLESS);
    }

    @Test
    void playInvalidatesCachedQqUrlBeforeReturningIt() {
        MusicCache cache = new MusicCache();
        musicService = new MusicService(cache, configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier);

        PlayInfo cached = new PlayInfo();
        cached.setId("song-mid");
        cached.setSource(MusicSource.QQ);
        cached.setActualSource(MusicSource.QQ);
        cached.setName("cached-track");
        cached.setArtist("cached-artist");
        cached.setPlayUrl("http://wx.music.tc.qq.com/M500cached.mp3?vkey=stale");
        cached.setActualQuality("128k");
        cache.put("music:play:qq:song-mid:128k", cached, 60);

        PlayInfo fresh = new PlayInfo();
        fresh.setId("song-mid");
        fresh.setSource(MusicSource.QQ);
        fresh.setActualSource(MusicSource.QQ);
        fresh.setName("fresh-track");
        fresh.setArtist("fresh-artist");
        fresh.setPlayUrl("https://cdn.cyapi.top/fresh-song.mid");
        fresh.setActualQuality("128k");

        when(playUrlVerifier.isPlayable(cached.getPlayUrl())).thenReturn(false);
        when(tfPay.play(MusicSource.QQ, "song-mid", MusicQuality.K128)).thenReturn(fresh);

        PlayInfo play = musicService.play("qq", "song-mid", "128k");

        assertEquals("https://cdn.cyapi.top/fresh-song.mid", play.getPlayUrl());
        assertEquals("128k", play.getRequestedQuality());
        assertFalse(Boolean.TRUE.equals(play.getFromCache()));
    }

    @Test
    void playReusesCachedQqHighResolverUrlForExplicitFlac() {
        MusicCache cache = new MusicCache();
        musicService = new MusicService(cache, configService, qq, netease, kuwo,
                chkszNetease, tfPay, qqTextFallback, playUrlVerifier);

        PlayInfo cached = new PlayInfo();
        cached.setId("song-mid");
        cached.setSource(MusicSource.QQ);
        cached.setActualSource(MusicSource.QQ);
        cached.setPlayUrl("https://isure6.stream.qqmusic.qq.com/C600song-mid.m4a?vkey=test");
        cached.setActualQuality("320k");
        cache.put("music:play:qq:song-mid:flac", cached, 60);
        when(playUrlVerifier.isPlayable(cached.getPlayUrl())).thenReturn(true);

        PlayInfo play = musicService.play("qq", "song-mid", "flac");

        assertEquals(cached.getPlayUrl(), play.getPlayUrl());
        assertEquals(MusicSource.QQ, play.getActualSource());
        assertEquals("flac", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
        assertTrue(Boolean.TRUE.equals(play.getFromCache()));
        verify(qq, never()).resolvePlay(anyString(), any(MusicQuality.class));
    }

    @Test
    void albumDetailRoutesToSourceAndCachesPage() {
        AlbumDetailView expected = new AlbumDetailView("album-mid", MusicSource.QQ, "Album", null,
                null, "Artist", "2026-08-05", 1, 20, 1, Collections.emptyList());
        when(qq.fetchAlbumDetail("album-mid", 1, 20)).thenReturn(expected);

        assertEquals(expected, musicService.albumDetail("qq", "album-mid", 1, 20));
        assertEquals(expected, musicService.albumDetail("qq", "album-mid", 1, 20));

        verify(qq).fetchAlbumDetail("album-mid", 1, 20);
    }

    @Test
    void recommendedPlaylistsRouteToEverySourceAndCacheResults() {
        PlaylistListView qqView = new PlaylistListView(MusicSource.QQ, "recommend", "recommend", 1, 20, 0L,
                Collections.emptyList());
        PlaylistListView neteaseView = new PlaylistListView(MusicSource.NETEASE, "recommend", "recommend", 1, 20, 0L,
                Collections.emptyList());
        PlaylistListView kuwoView = new PlaylistListView(MusicSource.KUWO, "recommend", "recommend", 1, 20, 0L,
                Collections.emptyList());
        when(qq.fetchRecommendedPlaylists(20)).thenReturn(qqView);
        when(netease.fetchRecommendedPlaylists(20)).thenReturn(neteaseView);
        when(kuwo.fetchRecommendedPlaylists(20)).thenReturn(kuwoView);

        assertEquals(qqView, musicService.recommendedPlaylists("qq", 20));
        assertEquals(neteaseView, musicService.recommendedPlaylists("netease", 20));
        assertEquals(kuwoView, musicService.recommendedPlaylists("kuwo", 20));
        assertEquals(qqView, musicService.recommendedPlaylists("qq", 20));

        verify(qq).fetchRecommendedPlaylists(20);
        verify(netease).fetchRecommendedPlaylists(20);
        verify(kuwo).fetchRecommendedPlaylists(20);
    }

    @Test
    void artistWorksRoutesAlbumTypeToKuwo() {
        ArtistWorksView expected = new ArtistWorksView("336", MusicSource.KUWO, "album", 1, 20,
                45, Collections.emptyList(), Collections.emptyList());
        when(kuwo.fetchArtistWorks("336", "album", 1, 20)).thenReturn(expected);

        assertEquals(expected, musicService.artistWorks("kuwo", "336", "ALBUM", 1, 20));

        verify(kuwo).fetchArtistWorks("336", "album", 1, 20);
    }

    private SongSearchItem song(String id, String name, String artist) {
        SongSearchItem item = new SongSearchItem();
        item.setId(id);
        item.setSource(MusicSource.QQ);
        item.setName(name);
        item.setArtist(artist);
        return item;
    }
}
