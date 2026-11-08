package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** Stable application entry point; each operation has an independently testable service. */
@Service
@RequiredArgsConstructor
public class MusicService {
    public static final String CFG_PLAY_RESOLVER_ORDER = MusicPlaybackService.CFG_PLAY_RESOLVER_ORDER;
    public static final String CFG_CROSS_SOURCE_ORDER = MusicPlaybackService.CFG_CROSS_SOURCE_ORDER;
    public static final String DEFAULT_PLAY_RESOLVER_ORDER = MusicPlaybackService.DEFAULT_PLAY_RESOLVER_ORDER;
    public static final String DEFAULT_CROSS_SOURCE_ORDER = MusicPlaybackService.DEFAULT_CROSS_SOURCE_ORDER;

    private final MusicSearchService searchService;
    private final MusicCatalogService catalogService;
    private final MusicPlaybackService playbackService;
    private final MusicLyricService lyricService;

    public SearchResultView search(String rawSource, String keyword, int page, int pageSize) {
        return searchService.search(rawSource, keyword, page, pageSize);
    }

    public SearchResultView search(String rawSource, String keyword, String rawType, int page, int pageSize) {
        return searchService.search(rawSource, keyword, rawType, page, pageSize);
    }

    public ToplistListView toplists(String rawSource) {
        return catalogService.toplists(rawSource);
    }

    public ToplistDetailView toplistDetail(String rawSource, String id, int page, int pageSize) {
        return catalogService.toplistDetail(rawSource, id, page, pageSize);
    }

    public PlaylistListView playlists(String rawSource, String category, String order, int page, int pageSize) {
        return catalogService.playlists(rawSource, category, order, page, pageSize);
    }

    public PlaylistListView recommendedPlaylists(String rawSource, int limit) {
        return catalogService.recommendedPlaylists(rawSource, limit);
    }

    public PlaylistDetailView playlistDetail(String rawSource, String id, int page, int pageSize) {
        return catalogService.playlistDetail(rawSource, id, page, pageSize);
    }

    public ToplistDetailView newSongs(String rawSource, int page, int pageSize) {
        return catalogService.newSongs(rawSource, page, pageSize);
    }

    public List<ArtistDetailView> getArtists(String rawSource, List<String> ids) {
        return catalogService.getArtists(rawSource, ids);
    }

    public AlbumDetailView albumDetail(String rawSource, String id, int page, int pageSize) {
        return catalogService.albumDetail(rawSource, id, page, pageSize);
    }

    public ArtistWorksView artistWorks(String rawSource, String id, String rawType, int page, int pageSize) {
        return catalogService.artistWorks(rawSource, id, rawType, page, pageSize);
    }

    public PlayInfo play(String rawSource, String id, String rawQuality) {
        return playbackService.play(rawSource, id, rawQuality);
    }

    public LyricView lyric(String rawSource, String id) {
        return lyricService.lyric(rawSource, id);
    }

}
