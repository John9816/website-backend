package com.example.website.module.music.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PlayUrlVerifier {

    private static final long VERDICT_TTL_SECONDS = 15L;
    private static final int MAX_VERDICTS = 10_000;

    private final OkHttpClient probeClient;
    private final Cache<String, Boolean> verdicts;

    public PlayUrlVerifier(OkHttpClient okHttpClient) {
        this.probeClient = okHttpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        this.verdicts = Caffeine.newBuilder()
                .maximumSize(MAX_VERDICTS)
                .expireAfterWrite(VERDICT_TTL_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    public boolean isPlayable(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return false;

        String normalized = rawUrl.trim();
        HttpUrl url = HttpUrl.parse(normalized);
        if (url == null) {
            log.info("Play URL probe skipped invalid url");
            return false;
        }

        Boolean cached = verdicts.getIfPresent(normalized);
        if (cached != null) {
            log.debug("Play URL probe verdict cache hit target={} playable={}", describe(url), cached);
            return cached;
        }

        boolean playable = probe(url);
        MusicRequestBudget.ensureNotCancelled();
        verdicts.put(normalized, playable);
        return playable;
    }

    private boolean probe(HttpUrl url) {

        Request req = new Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("Accept", "*/*")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();

        Call call = probeClient.newCall(req);
        MusicRequestBudget.constrain(call, 5, TimeUnit.SECONDS);
        try (Response resp = call.execute()) {
            int status = resp.code();
            if (status == 200 || status == 206) {
                return true;
            }
            log.info("Play URL probe rejected status={} target={}", status, describe(url));
            return false;
        } catch (IOException e) {
            MusicRequestBudget.ensureNotCancelled();
            log.info("Play URL probe failed target={} reason={}", describe(url), e.getMessage());
            return false;
        }
    }

    private static String describe(HttpUrl url) {
        return url.scheme() + "://" + url.host() + url.encodedPath();
    }
}
