package com.example.website.common;

import lombok.Getter;

@Getter
public class MusicBusinessException extends RuntimeException {

    private final MusicErrorCode errorCode;

    public MusicBusinessException(MusicErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public MusicBusinessException(MusicErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MusicBusinessException(MusicErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
