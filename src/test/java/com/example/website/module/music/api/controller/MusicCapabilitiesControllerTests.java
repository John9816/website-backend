package com.example.website.module.music.api.controller;
import com.example.website.common.GlobalExceptionHandler;
import com.example.website.module.identity.application.UserScopeService;
import com.example.website.module.music.application.*;
import com.example.website.module.music.api.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class MusicCapabilitiesControllerTests {
    final QishuiMusicClient catalog=mock(QishuiMusicClient.class);
    final TtmlLyricsClient lyrics=mock(TtmlLyricsClient.class);
    final MockMvc mvc=MockMvcBuilders.standaloneSetup(new MusicCapabilitiesController(new UserScopeService(null,null),catalog,lyrics)).setControllerAdvice(new GlobalExceptionHandler()).build();
    @Test void bothRoutesRequireLogin() throws Exception {
        mvc.perform(get("/api/v1/music/qishui/search/suggestions").param("keyword","x").param("userId","1"))
            .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/v1/music/lyrics/ttml").param("source","netease").param("id","186016"))
            .andExpect(status().isUnauthorized());verifyNoInteractions(catalog,lyrics);
    }
    @Test void songMetadataRequiresLoginAndValidIdentity() throws Exception {
        mvc.perform(get("/api/v1/music/qishui/song").param("id","123")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/music/qishui/song").param("id","../x").requestAttr("userId",7L)).andExpect(status().isBadRequest());
        verifyNoInteractions(catalog);
        SongSearchItem song = new SongSearchItem();song.setId("123");song.setArtistId("456");
        when(catalog.fetchSongInfo("123")).thenReturn(song);
        mvc.perform(get("/api/v1/music/qishui/song").param("id","123").requestAttr("userId",7L))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.artistId").value("456"));
    }
    @Test void loggedInRequestsUseAdditiveContracts() throws Exception {
        when(catalog.searchSuggestions("周")).thenReturn(Collections.singletonList("周杰伦"));
        mvc.perform(get("/api/v1/music/qishui/search/suggestions").param("keyword","周").requestAttr("userId",7L))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0]").value("周杰伦"));
        when(lyrics.fetch(MusicSource.QISHUI,"1")).thenReturn(new TtmlLyricsView(false,null,null));
        mvc.perform(get("/api/v1/music/lyrics/ttml").param("source","qishui").param("id","1").requestAttr("userId",7L))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false)).andExpect(header().string("Cache-Control","no-store"));
    }
}
