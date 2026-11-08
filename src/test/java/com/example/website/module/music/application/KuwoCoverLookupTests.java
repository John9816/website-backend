package com.example.website.module.music.application;

import com.example.website.module.music.api.dto.ToplistDetailView;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuwoCoverLookupTests {

    @Test
    void overlappingToplistPagesShareThePendingCoverRequest() throws Exception {
        AtomicInteger coverRequests = new AtomicInteger();
        CountDownLatch coverStarted = new CountDownLatch(1);
        CountDownLatch bothPagesLoaded = new CountDownLatch(2);
        CountDownLatch releaseCover = new CountDownLatch(1);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            if ("artistpicserver.kuwo.cn".equals(chain.request().url().host())) {
                coverRequests.incrementAndGet();
                coverStarted.countDown();
                await(releaseCover);
                return response(chain, "https://img1.kuwo.cn/cover.jpg");
            }
            bothPagesLoaded.countDown();
            return response(chain, "{\"name\":\"Chart\",\"pic\":\"https://img1.kuwo.cn/chart.jpg\","
                    + "\"info\":\"Info\",\"pub\":\"2026-11-08\",\"musiclist\":[{\"id\":\"42\","
                    + "\"name\":\"Song\",\"artist\":\"Artist\",\"artistid\":\"1\"}]}");
        }).build();
        KuwoMusicClient kuwo = new KuwoMusicClient(client, new ObjectMapper());
        CompletableFuture<ToplistDetailView> first = CompletableFuture.supplyAsync(() -> kuwo.fetchToplistDetail("17", 1, 10));
        CompletableFuture<ToplistDetailView> second = null;
        try {
            assertTrue(coverStarted.await(1, TimeUnit.SECONDS));
            second = CompletableFuture.supplyAsync(() -> kuwo.fetchToplistDetail("17", 2, 10));
            assertTrue(bothPagesLoaded.await(1, TimeUnit.SECONDS));
            // Both pages must finish their two-second wait while the single request is still pending.
            assertEquals(1, first.get(3, TimeUnit.SECONDS).getList().size());
            assertEquals(1, second.get(3, TimeUnit.SECONDS).getList().size());
            assertEquals(1, coverRequests.get());
        } finally {
            releaseCover.countDown();
            if (second != null) second.get(3, TimeUnit.SECONDS);
            first.get(3, TimeUnit.SECONDS);
            kuwo.close();
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("fixture deadline");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
    }

    private static Response response(Interceptor.Chain chain, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(ResponseBody.create(body, MediaType.get("text/plain; charset=utf-8"))).build();
    }
}
