package com.example.website.service.video;

import com.example.website.common.BusinessException;

/** Stable boundary error for an unavailable or invalid video Provider. */
public class VideoProviderException extends BusinessException {
    public VideoProviderException(String message) {
        super(502, message);
    }
}
