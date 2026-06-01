package com.example.website.dto.music;

import java.util.Locale;

public enum MusicSearchType {
    SONG("song"),
    ALBUM("album"),
    ARTIST("artist"),
    PLAYLIST("playlist");

    private final String value;

    MusicSearchType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static MusicSearchType of(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return SONG;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("songs".equals(normalized) || "track".equals(normalized) || "tracks".equals(normalized)) {
            return SONG;
        }
        if ("albums".equals(normalized)) {
            return ALBUM;
        }
        if ("artists".equals(normalized) || "singer".equals(normalized) || "singers".equals(normalized)) {
            return ARTIST;
        }
        if ("playlists".equals(normalized) || "songlist".equals(normalized) || "songlists".equals(normalized)) {
            return PLAYLIST;
        }
        for (MusicSearchType type : values()) {
            if (type.value.equals(normalized)) {
                return type;
            }
        }
        return SONG;
    }
}
