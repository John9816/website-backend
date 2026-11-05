package com.example.website.controller;

import com.example.website.dto.music.MusicSource;
import com.example.website.dto.music.PlayInfo;
import com.example.website.service.MembershipService;
import com.example.website.service.music.MusicLibraryService;
import com.example.website.service.music.MusicService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MusicControllerTests {

    @Test
    void prefetchStillChecksMembershipButDoesNotWriteHistory() {
        MusicService musicService = mock(MusicService.class);
        MusicLibraryService libraryService = mock(MusicLibraryService.class);
        MembershipService membershipService = mock(MembershipService.class);
        MusicController controller = new MusicController(musicService, libraryService, membershipService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 42L);
        PlayInfo info = new PlayInfo();
        info.setId("song-id");
        info.setSource(MusicSource.QQ);
        when(musicService.play("qq", "song-id", "320k")).thenReturn(info);

        controller.play(request, "qq", "song-id", "320k", true);

        verify(membershipService).requireActiveMembership(42L);
        verify(libraryService, never()).recordPlay(42L, info);

        controller.play(request, "qq", "song-id", "320k", false);

        verify(membershipService, times(2)).requireActiveMembership(42L);
        verify(libraryService).recordPlay(42L, info);
    }
}
