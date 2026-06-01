package com.example.website.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business-code / HTTP-status pairs for the /api/v1/music/* endpoints.
 * Codes (1001-1012) are what the frontend switches on.
 */
@Getter
public enum MusicErrorCode {

    INVALID_SOURCE(1001, HttpStatus.UNPROCESSABLE_ENTITY, "invalid source"),
    INVALID_QUALITY(1002, HttpStatus.UNPROCESSABLE_ENTITY, "invalid quality"),
    MISSING_UPSTREAM_TOKEN(1003, HttpStatus.BAD_GATEWAY, "missing upstream token"),
    UPSTREAM_SEARCH_FAILED(1004, HttpStatus.BAD_GATEWAY, "upstream search failed"),
    UPSTREAM_PLAY_FAILED(1005, HttpStatus.BAD_GATEWAY, "upstream play failed"),
    UPSTREAM_LYRIC_FAILED(1006, HttpStatus.BAD_GATEWAY, "upstream lyric failed"),
    SONG_NOT_FOUND(1007, HttpStatus.NOT_FOUND, "song not found"),
    NO_PLAYABLE_URL(1008, HttpStatus.BAD_GATEWAY, "no playable url"),
    UPSTREAM_TIMEOUT(1009, HttpStatus.GATEWAY_TIMEOUT, "upstream timeout"),
    UPSTREAM_TOPLIST_FAILED(1010, HttpStatus.BAD_GATEWAY, "upstream toplist failed"),
    UPSTREAM_PLAYLIST_FAILED(1011, HttpStatus.BAD_GATEWAY, "upstream playlist failed"),
    UNSUPPORTED_OPERATION(1012, HttpStatus.NOT_IMPLEMENTED, "unsupported operation");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    MusicErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
