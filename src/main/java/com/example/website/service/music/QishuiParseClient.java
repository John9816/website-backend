package com.example.website.service.music;

import com.example.website.common.*;
import com.example.website.dto.music.*;
import com.fasterxml.jackson.databind.*;

import okhttp3.HttpUrl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;

/** TuneFree v1 uses a different account/token from the legacy tf-pay service. */
@Component
public class QishuiParseClient {
    private final QishuiHttpClient http;
    private final ObjectMapper mapper;
    private final String accessToken;

    public QishuiParseClient(
            QishuiHttpClient http,
            ObjectMapper mapper,
            @Value("${app.music.qishui.parse-access-token:}") String accessToken) {
        this.http = http;
        this.mapper = mapper;
        this.accessToken = accessToken;
    }

    public PlayInfo play(String id, MusicQuality quality) {
        if (accessToken == null || accessToken.trim().isEmpty())
            throw new MusicBusinessException(
                    MusicErrorCode.MISSING_UPSTREAM_TOKEN,
                    "TuneFree v1 parser access token is not configured");
        JsonNode root =
                http.post(
                                "https://tunefree.sayqz.com/v1/parse",
                                null,
                                mapper.createObjectNode()
                                        .put("source", "qs")
                                        .put("trackId", id)
                                        .put("quality", quality.getValue()),
                                Collections.singletonMap(
                                        "Authorization", "Bearer " + accessToken.trim()))
                        .json;
        if (!root.path("success").asBoolean())
            throw new MusicBusinessException(
                    MusicErrorCode.UPSTREAM_PLAY_FAILED, "TuneFree v1 parse failed");
        JsonNode data = root.path("data");
        if (data.hasNonNull("encryption"))
            throw QishuiMusicClient.unsupported(
                    "encrypted audio requires a compatible player; a plain playUrl cannot be"
                        + " returned");
        String url = data.path("playUrl").asText("");
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !("https".equals(parsed.scheme()) || "http".equals(parsed.scheme())))
            throw new MusicBusinessException(MusicErrorCode.NO_PLAYABLE_URL);
        PlayInfo p = new PlayInfo();
        p.setSource(MusicSource.QISHUI);
        p.setId(id);
        p.setActualSource(MusicSource.QISHUI);
        p.setActualId(id);
        p.setLyricMatch("exact");
        p.setPlayUrl(url);
        p.setRequestedQuality(quality.getValue());
        p.setActualQuality(data.path("actualQuality").asText(quality.getValue()));
        p.setFromCache(false);
        // Unknown expiry is deliberately not cached.
        int ttl = 0;
        try {
            ttl =
                    (int)
                            Math.max(
                                    0,
                                    Math.min(
                                            1200,
                                            Instant.parse(data.path("expiresAt").asText())
                                                            .getEpochSecond()
                                                    - Instant.now().getEpochSecond()));
        } catch (RuntimeException ignored) {
        }
        p.setExpireSec(ttl);
        return p;
    }
}
