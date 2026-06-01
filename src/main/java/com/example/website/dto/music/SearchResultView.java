package com.example.website.dto.music;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultView {

    private MusicSource source;
    private MusicSearchType type;
    private String keyword;
    private int page;
    private int pageSize;
    private Long total;
    private List<SongSearchItem> list;
    private List<SongSearchItem> songs;
    private List<SearchCollectionItem> artists;
    private List<SearchCollectionItem> albums;
    private List<SearchCollectionItem> playlists;

    public static SearchResultView songs(MusicSource source,
                                         String keyword,
                                         int page,
                                         int pageSize,
                                         Long total,
                                         List<SongSearchItem> list) {
        SearchResultView view = new SearchResultView();
        view.source = source;
        view.type = MusicSearchType.SONG;
        view.keyword = keyword;
        view.page = page;
        view.pageSize = pageSize;
        view.total = total;
        view.list = list;
        view.songs = list;
        view.artists = Collections.emptyList();
        view.albums = Collections.emptyList();
        view.playlists = Collections.emptyList();
        return view;
    }

    public static SearchResultView collections(MusicSource source,
                                               MusicSearchType type,
                                               String keyword,
                                               int page,
                                               int pageSize,
                                               Long total,
                                               List<SearchCollectionItem> items) {
        SearchResultView view = new SearchResultView();
        view.source = source;
        view.type = type;
        view.keyword = keyword;
        view.page = page;
        view.pageSize = pageSize;
        view.total = total;
        view.list = Collections.emptyList();
        view.songs = Collections.emptyList();
        view.artists = type == MusicSearchType.ARTIST ? items : Collections.emptyList();
        view.albums = type == MusicSearchType.ALBUM ? items : Collections.emptyList();
        view.playlists = type == MusicSearchType.PLAYLIST ? items : Collections.emptyList();
        return view;
    }
}
