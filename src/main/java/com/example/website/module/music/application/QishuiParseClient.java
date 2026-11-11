package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.PlayInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Resolves Qishui playback into a local, signed audio stream URL, and — when possible — also
 * returns the rich-client direct URL + CENC play auth so the browser can fetch and decrypt the
 * audio itself, keeping the bytes off our ECS egress.
 *
 * <p>TuneFree v1 always returns CENC-encrypted audio (verified for 128k/320k/flac/flac24bit),
 * which a browser {@code <audio>} element cannot play. Rather than rejecting with 1012, this
 * client issues a short-lived ticket and returns the URL of {@link QishuiStreamController},
 * which performs login, parse, download and in-process decryption, then serves plain MP4. That
 * URL is the reliable fallback kept on every response.
 *
 * <p>On top of the ticket, {@link #play} best-effort resolves the upstream CDN URL (post-302) and
 * the CENC play auth from {@link QishuiStreamService#resolveDirect} and attaches them as
 * {@code directUrl}/{@code playAuth}. If that fails for any reason the fallback URL still works,
 * so the browser degrades gracefully. With both present the rich-client path runs entirely
 * client-side: zero audio bytes cross our ECS, and the backend only signs and points.
 */
@Slf4j
@Component
public class QishuiParseClient {

    private static final String STREAM_PATH = "/api/public/music/qishui/stream";
    private final QishuiStreamTicket tickets;
    private final QishuiStreamService streams;
    private final String publicBaseUrl;

    public QishuiParseClient(
            QishuiStreamTicket tickets,
            @Value("${app.music.qishui.public-base-url:}") String publicBaseUrl,
            QishuiStreamService streams) {
        this.tickets = tickets;
        this.streams = streams;
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.publicBaseUrl = base;
    }

    public PlayInfo play(String id, MusicQuality quality) {
        String q = quality == null ? MusicQuality.K320.getValue() : quality.getValue();
        String ticket = tickets.issue(id, q);
        String stream = STREAM_PATH + "?ticket="
                + URLEncoder.encode(ticket, StandardCharsets.UTF_8);
        // An empty public base URL yields a same-origin relative path. The browser resolves it
        // against the page origin, so playback never depends on a separate subdomain (which can
        // be blocked by DNS, proxies, extensions or privacy policies before a request is even
        // sent -> <audio> reports MEDIA_ERR_SRC_NOT_SUPPORTED with a misleading "expired" hint).
        // Set QISHUI_PUBLIC_BASE_URL only when the frontend and API are on different origins.
        String url = publicBaseUrl.isEmpty() ? stream : publicBaseUrl + stream;

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

        // Rich-client direct URL: let the browser fetch + decrypt the audio itself so the bytes
        // never traverse our ECS egress. A failure here must not break playback — the local
        // stream ticket above is a fully working fallback, so we swallow and continue.
        try {
            QishuiStreamService.QishuiResolvedPlayback resolved = streams.resolveDirect(id, q);
            if (resolved != null && resolved.directUrl != null && !resolved.directUrl.isEmpty()) {
                p.setDirectUrl(resolved.directUrl);
                p.setPlayAuth(resolved.playAuth);
            }
        } catch (Exception e) {
            log.warn("Qishui direct playback resolution failed; using stream-ticket fallback: {}",
                    e.getMessage());
        }
        return p;
    }
}
