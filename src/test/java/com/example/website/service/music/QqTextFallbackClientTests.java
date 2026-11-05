package com.example.website.service.music;

import com.example.website.dto.music.MusicQuality;
import com.example.website.dto.music.PlayInfo;
import com.example.website.dto.music.SongSearchItem;
import com.example.website.service.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Optional;

import okio.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QqTextFallbackClientTests {

    @Mock
    private OkHttpClient okHttpClient;

    @Mock
    private Call call;

    @Mock
    private SysConfigService configService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(call.timeout()).thenReturn(new Timeout());
        org.mockito.Mockito.lenient().when(configService.getValue(QqTextFallbackClient.CFG_API_KEY))
                .thenReturn(Optional.of("test-api-key-never-log"));
    }

    @Test
    void fetchSongUrlBuildsKeywordAndParsesTopLevelUrlAndLyric() throws IOException {
        QqTextFallbackClient client = new QqTextFallbackClient(okHttpClient, new ObjectMapper(), configService);
        SongSearchItem meta = song("track-one", "artist-one");

        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(okResponse("{"
                + "\"name\":\"track-one\","
                + "\"artists\":[{\"name\":\"artist-one\"}],"
                + "\"album\":{\"name\":\"album-one\"},"
                + "\"quality\":{\"current\":\"high\"},"
                + "\"url\":\"https://example.com/fallback.m4a\","
                + "\"lyric\":{\"text\":\"[00:00.00]line-one\"}"
                + "}"));

        PlayInfo info = client.fetchSongUrl("song-mid", MusicQuality.K128, meta);

        assertEquals("song-mid", info.getId());
        assertEquals("track-one", info.getName());
        assertEquals("artist-one", info.getArtist());
        assertEquals("album-one", info.getAlbum());
        assertEquals("https://example.com/fallback.m4a", info.getPlayUrl());
        assertEquals("320k", info.getActualQuality());
        assertNotNull(info.getLyric());
        assertEquals("[00:00.00]line-one", info.getLyric().getLineLyrics());

        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(captor.capture());
        assertEquals("json", captor.getValue().url().queryParameter("type"));
        assertEquals("1", captor.getValue().url().queryParameter("n"));
        assertEquals("track-one artist-one", captor.getValue().url().queryParameter("msg"));
        assertTrue(captor.getValue().url().queryParameter("apikey").length() > 10);
    }

    @Test
    void fetchSongUrlSupportsNestedDataMusicUrlAndLrcFallback() throws IOException {
        QqTextFallbackClient client = new QqTextFallbackClient(okHttpClient, new ObjectMapper(), configService);
        SongSearchItem meta = song("track-two", "artist-two");
        meta.setAlbum("album-two");

        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(okResponse("{"
                + "\"data\":{"
                + "\"music_url\":\"https://example.com/fallback.flac\","
                + "\"lrc\":\"[00:01.00]line-two\""
                + "}"
                + "}"));

        PlayInfo info = client.fetchSongUrl("song-mid", MusicQuality.FLAC, meta);

        assertEquals("track-two", info.getName());
        assertEquals("artist-two", info.getArtist());
        assertEquals("album-two", info.getAlbum());
        assertEquals("https://example.com/fallback.flac", info.getPlayUrl());
        assertEquals("flac", info.getActualQuality());
        assertEquals("[00:01.00]line-two", info.getLyric().getLineLyrics());
    }

    @Test
    void networkFailureIsNegativeCached() throws IOException {
        QqTextFallbackClient client = new QqTextFallbackClient(okHttpClient, new ObjectMapper(), configService);
        SongSearchItem meta = song("track-three", "artist-three");

        when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("offline"));

        assertThrows(RuntimeException.class,
                () -> client.fetchSongUrl("song-mid", MusicQuality.K128, meta));
        assertThrows(RuntimeException.class,
                () -> client.fetchSongUrl("song-mid", MusicQuality.K128, meta));

        verify(okHttpClient, times(1)).newCall(any(Request.class));
    }

    @Test
    void missingConfiguredKeyDisablesOnlyThisResolverWithoutNetworkCall() {
        when(configService.getValue(QqTextFallbackClient.CFG_API_KEY)).thenReturn(Optional.empty());
        QqTextFallbackClient client = new QqTextFallbackClient(okHttpClient, new ObjectMapper(), configService);

        com.example.website.common.MusicBusinessException error = assertThrows(
                com.example.website.common.MusicBusinessException.class,
                () -> client.fetchSongUrl("song-mid", MusicQuality.K128, song("track", "artist")));

        assertEquals(com.example.website.common.MusicErrorCode.MISSING_UPSTREAM_TOKEN, error.getErrorCode());
        verify(okHttpClient, never()).newCall(any(Request.class));
    }

    private SongSearchItem song(String name, String artist) {
        SongSearchItem item = new SongSearchItem();
        item.setName(name);
        item.setArtist(artist);
        return item;
    }

    private Response okResponse(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://cyapi.top/API/qq_music.php").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();
    }
}
