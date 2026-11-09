package com.example.website.module.music.api.controller;

import com.example.website.module.music.application.QishuiStreamService;
import com.example.website.module.music.application.QishuiStreamTicket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * Local, plain-audio endpoint for Qishui (TuneFree v1) playback.
 *
 * <p>Registered under {@code /api/public/**} which is {@code permitAll} for GET, because a
 * browser {@code <audio>} element cannot attach an Authorization header. Access is instead
 * gated by a short-lived HMAC ticket bound to one track/quality (see {@link QishuiStreamTicket}).
 * HTTP Range is supported so seeking works without re-resolving the whole track.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/music/qishui")
@RequiredArgsConstructor
public class QishuiStreamController {

    private static final MediaType AUDIO_MP4 = MediaType.valueOf("audio/mp4");
    private final QishuiStreamTicket tickets;
    private final QishuiStreamService streams;

    @GetMapping(value = "/stream", produces = "audio/mp4")
    public ResponseEntity<byte[]> stream(
            @RequestParam(value = "ticket", required = false) String ticket,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        // verify() handles null/empty as a 400 business error; a required @RequestParam would
        // instead raise MissingServletRequestParameterException -> generic 500.
        QishuiStreamTicket.Payload payload = tickets.verify(ticket);
        byte[] audio = streams.plaintext(payload.getTrackId(), payload.getQuality());

        Range range = Range.parse(rangeHeader, audio.length);
        if (range == null) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + audio.length)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .build();
        }
        byte[] body = range.start == 0 && range.end == audio.length - 1
                ? audio
                : Arrays.copyOfRange(audio, range.start, range.end + 1);
        HttpStatus status = range.partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(AUDIO_MP4)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(body.length));
        if (range.partial) {
            builder.header(HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start + "-" + range.end + "/" + audio.length);
        }
        return builder.body(body);
    }

    private static final class Range {
        final int start;
        final int end;
        final boolean partial;

        Range(int start, int end, boolean partial) {
            this.start = start;
            this.end = end;
            this.partial = partial;
        }

        /** Returns null when the requested range is unsatisfiable. */
        static Range parse(String header, int length) {
            if (header == null || header.isEmpty()) {
                return new Range(0, length - 1, false);
            }
            String v = header.trim();
            if (!v.startsWith("bytes=")) return new Range(0, length - 1, false);
            String spec = v.substring(6).split(",", 2)[0].trim();
            int dash = spec.indexOf('-');
            if (dash < 0) return null;
            try {
                long start;
                long end;
                if (dash == 0) { // suffix range: bytes=-N
                    long suffix = Long.parseLong(spec.substring(1));
                    if (suffix <= 0) return null;
                    start = Math.max(0, length - suffix);
                    end = length - 1;
                } else {
                    start = Long.parseLong(spec.substring(0, dash));
                    end = spec.length() > dash + 1
                            ? Long.parseLong(spec.substring(dash + 1))
                            : length - 1;
                }
                if (start >= length || start < 0 || end < start) return null;
                end = Math.min(end, length - 1);
                return new Range((int) start, (int) end, true);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
