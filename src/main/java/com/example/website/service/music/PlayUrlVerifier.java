package com.example.website.service.music;

import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PlayUrlVerifier {

    private final OkHttpClient probeClient;

    public PlayUrlVerifier(OkHttpClient okHttpClient) {
        this.probeClient = okHttpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public boolean isPlayable(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return false;

        HttpUrl url = HttpUrl.parse(rawUrl.trim());
        if (url == null) {
            log.info("Play URL probe skipped invalid url");
            return false;
        }

        Request req = new Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("Accept", "*/*")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build();

        try (Response resp = probeClient.newCall(req).execute()) {
            int status = resp.code();
            if (status == 200 || status == 206) {
                return true;
            }
            log.info("Play URL probe rejected status={} target={}", status, describe(url));
            return false;
        } catch (IOException e) {
            log.info("Play URL probe failed target={} reason={}", describe(url), e.getMessage());
            return false;
        }
    }

    private static String describe(HttpUrl url) {
        return url.scheme() + "://" + url.host() + url.encodedPath();
    }
}
