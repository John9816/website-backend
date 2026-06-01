package com.example.website.service.music;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.dto.music.LyricInfo;
import com.example.website.dto.music.LyricView;
import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.dto.music.SongSearchItem;
import com.example.website.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MusicServiceTests {

    @Mock
    private QqMusicClient qq;

    @Mock
    private NeteaseMusicClient netease;

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
        musicService = new MusicService(new MusicCache(), configService, qq, netease, kuwo, tfPay, qqTextFallback, playUrlVerifier);
    }

    @Test
    void playUsesQqTextFallbackAndSeedsLyricCacheWhenPrimaryResolverFails() {
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

        PlayInfo play = musicService.play("qq", "song-mid", "128k");

        assertEquals("song-mid", play.getId());
        assertEquals(MusicSource.QQ, play.getSource());
        assertEquals(MusicSource.QQ, play.getActualSource());
        assertEquals("https://example.com/fallback.m4a", play.getPlayUrl());
        assertEquals("128k", play.getRequestedQuality());
        assertEquals("320k", play.getActualQuality());
        assertEquals("[00:00.00]line-one", play.getLyric().getLineLyrics());

        LyricView lyric = musicService.lyric("qq", "song-mid");
        assertEquals("[00:00.00]line-one", lyric.getLineLyrics());
        assertNull(lyric.getKaraokeLyrics());
        verify(qq, never()).fetchLyric(anyString());
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
    }

    @Test
    void playInvalidatesCachedQqUrlBeforeReturningIt() {
        MusicCache cache = new MusicCache();
        musicService = new MusicService(cache, configService, qq, netease, kuwo, tfPay, qqTextFallback, playUrlVerifier);

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
    void playResolverOrderCanPreferQqTextFallbackBeforePrimary() {
        SongSearchItem meta = song("song-mid", "track-four", "artist-four");
        PlayInfo fallback = new PlayInfo();
        fallback.setId("song-mid");
        fallback.setSource(MusicSource.QQ);
        fallback.setActualSource(MusicSource.QQ);
        fallback.setName("track-four");
        fallback.setArtist("artist-four");
        fallback.setPlayUrl("https://cdn.cyapi.top/text-first.m4a");
        fallback.setActualQuality("320k");

        when(configService.getValue(MusicService.CFG_PLAY_RESOLVER_ORDER))
                .thenReturn(Optional.of("qq_text,primary,cross_source"));
        when(qq.fetchSongInfo("song-mid")).thenReturn(meta);
        when(qqTextFallback.fetchSongUrl("song-mid", MusicQuality.K128, meta)).thenReturn(fallback);

        PlayInfo play = musicService.play("qq", "song-mid", "128k");

        assertEquals("https://cdn.cyapi.top/text-first.m4a", play.getPlayUrl());
        verify(tfPay, never()).play(MusicSource.QQ, "song-mid", MusicQuality.K128);
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
