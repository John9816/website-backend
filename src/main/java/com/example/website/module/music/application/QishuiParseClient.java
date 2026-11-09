package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Resolves Qishui playback into a local, signed audio stream URL.
 *
 * <p>TuneFree v1 always returns CENC-encrypted audio (verified for 128k/320k/flac/flac24bit),
 * which a browser {@code <audio>} element cannot play. Rather than rejecting with 1012, this
 * client issues a short-lived ticket and returns the URL of {@link QishuiStreamController},
 * which performs login, parse, download and in-process decryption, then serves plain MP4.
 *
 * <p>The upstream play URL and content key never leave the backend.
 */
@Component
public class QishuiParseClient {

    private static final String STREAM_PATH = "/api/public/music/qishui/stream";
    private final QishuiStreamTicket tickets;
    private final String publicBaseUrl;

    public QishuiParseClient(
            QishuiStreamTicket tickets,
            @Value("${app.music.qishui.public-base-url:https://api.751152.xyz}") String publicBaseUrl) {
        this.tickets = tickets;
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.publicBaseUrl = base;
    }

    public PlayInfo play(String id, MusicQuality quality) {
        String q = quality == null ? MusicQuality.K320.getValue() : quality.getValue();
        String ticket = tickets.issue(id, q);
        String url = publicBaseUrl + STREAM_PATH + "?ticket="
                + URLEncoder.encode(ticket, StandardCharsets.UTF_8);

        PlayInfo p = new PlayInfo();
        p.setSource(MusicSource.QISHUI);
        p.setId(id);
        p.setActualSource(MusicSource.QISHUI);
        p.setActualId(id);
        p.setLyricMatch("exact");
        p.setPlayUrl(url);
        p.setRequestedQuality(q);
        p.setActualQuality(q);
        p.setFromCache(false);
        // The ticket is valid for 1200s; leave a safety margin so the play cache refreshes first.
        p.setExpireSec(1080);
        return p;
    }
}
