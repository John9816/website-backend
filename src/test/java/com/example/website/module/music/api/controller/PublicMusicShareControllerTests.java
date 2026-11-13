package com.example.website.module.music.api.controller;

import com.example.website.common.BusinessException;
import com.example.website.common.GlobalExceptionHandler;
import com.example.website.module.music.api.dto.MusicShareMetaView;
import com.example.website.module.music.application.MusicShareService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicMusicShareControllerTests {

    private MusicShareService shareService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        shareService = mock(MusicShareService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PublicMusicShareController(shareService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void metaIsReadableWithoutAuthenticationAndDoesNotOpenFullShare() throws Exception {
        MusicShareMetaView meta = new MusicShareMetaView();
        meta.setName("Song name");
        meta.setArtist("Artist");
        meta.setCoverUrl("https://example.test/cover.jpg");
        when(shareService.meta("share-token")).thenReturn(meta);

        mvc.perform(get("/api/public/music/share/share-token/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("Song name"))
                .andExpect(jsonPath("$.data.artist").value("Artist"))
                .andExpect(jsonPath("$.data.coverUrl").value("https://example.test/cover.jpg"));

        verify(shareService).meta("share-token");
        verify(shareService, never()).view(anyString());
    }

    @Test
    void unknownShareReturnsHttp404() throws Exception {
        when(shareService.meta("missing")).thenThrow(new BusinessException(404, "Share not found"));

        mvc.perform(get("/api/public/music/share/missing/meta"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void expiredShareReturnsHttp410() throws Exception {
        when(shareService.meta("expired")).thenThrow(new BusinessException(410, "Share link expired"));

        mvc.perform(get("/api/public/music/share/expired/meta"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(410));
    }
}
