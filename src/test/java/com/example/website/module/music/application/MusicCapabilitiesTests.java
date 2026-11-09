package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.module.music.api.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.website.module.music.application.QishuiClientTests.*;

class MusicCapabilitiesTests {
    final ObjectMapper mapper = new ObjectMapper();
    @Test void suggestionsUsePublicContractDeduplicateAndCache() {
        AtomicInteger calls = new AtomicInteger();
        QishuiMusicClient client = new QishuiMusicClient(new QishuiHttpClient(new OkHttpClient.Builder().addInterceptor(c -> {
            if(c.request().url().encodedPath().contains("token/get")) return response(c,"{\"err_no\":0,\"client_token\":\"test\",\"expires_time\":4102444800}");
            calls.incrementAndGet();assertTrue(c.request().url().encodedPath().endsWith("/search/sug/"));
            assertEquals("周",mapper.readTree(body(c.request())).path("q").asText());
            return response(c,"{\"err_no\":0,\"data\":{\"sugs\":[{\"suggestion\":\"周杰伦\"},{\"suggestion\":\"周杰伦\"},{\"suggestion\":\" \"}]}}");
        }).build(),mapper),mapper);
        assertEquals(Collections.singletonList("周杰伦"),client.searchSuggestions(" 周 "));
        assertEquals(Collections.singletonList("周杰伦"),client.searchSuggestions("周"));
        assertEquals(1,calls.get());assertTrue(client.searchSuggestions(" ").isEmpty());
        assertThrows(BusinessException.class,()->client.searchSuggestions(String.join("",Collections.nCopies(121,"x"))));
    }
    static String shareHtml() {
        return "<script>window._ROUTER_DATA = {\"loaderData\":{\"playlist_page\":{\"playlistInfo\":{\"id\":\"123456\",\"title\":\"Mix } title\",\"count_tracks\":3},\"medias\":[{\"type\":\"video\"},{\"type\":\"track\",\"entity\":{\"track\":{\"id\":\"9876543210123456789\",\"name\":\"Song\",\"duration\":120000,\"album\":{\"id\":\"12\",\"name\":\"Album\"},\"artists\":[{\"id\":\"34\",\"name\":\"Artist\"}]}}}]}}};alert('never execute');</script>";
    }
    @Test void sharePaginationCountsOnlyAvailableTracksAndPreservesLongIds() {
        AtomicInteger calls = new AtomicInteger();
        QishuiShareClient client = new QishuiShareClient(new OkHttpClient.Builder().addInterceptor(c->{calls.incrementAndGet();return response(c,shareHtml());}).build(),mapper);
        assertEquals("share:123456",client.resolve("听歌 https://music.douyin.com/qishui/share/playlist?playlist_id=123456"));
        PlaylistDetailView page=client.fetch("share:123456",1,1);
        assertEquals(1,page.getTotal());assertTrue(page.getDescription().contains("3"));
        assertEquals("9876543210123456789",page.getList().get(0).getId());assertEquals(120000L,page.getList().get(0).getDurationMs());
        page.getList().clear();assertEquals(1,client.fetch("share:123456",1,1).getList().size());
        assertTrue(client.fetch("share:123456",2,1).getList().isEmpty());assertEquals(1,calls.get());
    }
    @Test void shareRejectsForeignUrlsAndRedirectsAndMissingRouterData() {
        AtomicInteger calls=new AtomicInteger();
        QishuiShareClient c=new QishuiShareClient(new OkHttpClient.Builder().addInterceptor(ch->{calls.incrementAndGet();return response(ch,"").newBuilder().code(302).header("Location","http://127.0.0.1/private").build();}).build(),mapper);
        for(String u:Arrays.asList("https://qishui.com.evil.test/?playlist_id=123456","http://127.0.0.1/","https://user@qishui.com/","https://qishui.com:8443/"))assertThrows(BusinessException.class,()->c.resolve(u));
        assertEquals(0,calls.get());assertThrows(BusinessException.class,()->c.resolve("https://qishui.douyin.com/short/"));assertEquals(1,calls.get());
        assertThrows(BusinessException.class,()->c.parse("share:123456","<script>other={}</script>"));
    }
    @Test void ttmlContractIsSourceSpecificCachedAndOptional() {
        AtomicInteger calls=new AtomicInteger();
        TtmlLyricsClient c=new TtmlLyricsClient(new OkHttpClient.Builder().addInterceptor(ch->{calls.incrementAndGet();assertEquals("https://lyrics.sayqz.com/v1/ttml/wy/186016",ch.request().url().toString());return response(ch,"<tt xmlns='http://www.w3.org/ns/ttml'><body><div><p begin='1s' end='2s'>Hello</p></div></body></tt>");}).build());
        assertTrue(c.fetch(MusicSource.NETEASE,"186016").isAvailable());c.fetch(MusicSource.NETEASE,"186016");assertEquals(1,calls.get());
        assertFalse(c.fetch(MusicSource.QISHUI,"123456").isAvailable());assertEquals(1,calls.get());
        assertThrows(BusinessException.class,()->c.fetch(MusicSource.QQ,"../../secret"));
    }
    @Test void ttmlErrorsAndUnsafeXmlFallBackWithoutExposingUpstream() {
        for(String xml:Arrays.asList("<html>secret error</html>","<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><tt>&x;</tt>","<tt><broken></tt>")){
            TtmlLyricsClient c=new TtmlLyricsClient(new OkHttpClient.Builder().addInterceptor(ch->response(ch,xml)).build());
            assertFalse(c.fetch(MusicSource.QQ,"abc").isAvailable());assertNull(c.fetch(MusicSource.QQ,"abc").getContent());
        }
    }
}
