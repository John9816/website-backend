package com.example.website.module.music.application;

import com.example.website.common.BusinessException;
import com.example.website.module.music.api.dto.MusicShareMetaView;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.domain.MusicShare;
import com.example.website.module.music.infrastructure.persistence.MusicShareRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MusicShareServiceTests {

    @Mock
    private MusicShareRepository shareRepository;

    @Mock
    private MusicService musicService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private MusicShareService shareService;

    @BeforeEach
    void setUp() {
        shareService = new MusicShareService(shareRepository, musicService, transactionTemplate);
    }

    @Test
    void metaReturnsStoredTrackWithoutResolvingOrCountingViews() {
        MusicShare share = share();
        when(shareRepository.findByToken("share-token")).thenReturn(Optional.of(share));

        MusicShareMetaView meta = shareService.meta("share-token");

        assertEquals("share-token", meta.getToken());
        assertEquals(MusicSource.QQ, meta.getSource());
        assertEquals("song-id", meta.getSongId());
        assertEquals("Song name", meta.getName());
        assertEquals("Artist", meta.getArtist());
        assertEquals("Album", meta.getAlbum());
        assertEquals("https://example.test/cover.jpg", meta.getCoverUrl());
        assertEquals(213, meta.getDurationSec());
        assertEquals(7, share.getViewCount());
        verify(shareRepository).findByToken("share-token");
        verifyNoMoreInteractions(shareRepository);
        verifyNoInteractions(musicService, transactionTemplate);
    }

    @Test
    void metaReturnsNotFoundForUnknownToken() {
        when(shareRepository.findByToken("missing")).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class, () -> shareService.meta("missing"));

        assertEquals(404, error.getCode());
        verify(shareRepository).findByToken("missing");
        verifyNoMoreInteractions(shareRepository);
        verifyNoInteractions(musicService, transactionTemplate);
    }

    @Test
    void metaReturnsGoneForExpiredShareWithoutSideEffects() {
        MusicShare share = share();
        share.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(shareRepository.findByToken("share-token")).thenReturn(Optional.of(share));

        BusinessException error = assertThrows(BusinessException.class, () -> shareService.meta("share-token"));

        assertEquals(410, error.getCode());
        assertEquals(7, share.getViewCount());
        verify(shareRepository).findByToken("share-token");
        verifyNoMoreInteractions(shareRepository);
        verifyNoInteractions(musicService, transactionTemplate);
    }

    private MusicShare share() {
        MusicShare share = new MusicShare();
        share.setId(1L);
        share.setToken("share-token");
        share.setSource("qq");
        share.setSongId("song-id");
        share.setName("Song name");
        share.setArtist("Artist");
        share.setAlbum("Album");
        share.setCoverUrl("https://example.test/cover.jpg");
        share.setDurationSec(213);
        share.setViewCount(7);
        share.setExpiresAt(LocalDateTime.now().plusHours(1));
        return share;
    }
}
