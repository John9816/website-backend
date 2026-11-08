package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QishuiTokenConcurrencyTests {
    @Test
    void delayedFailureCannotInvalidateRefreshedGenerationEvenWhenTokenStringIsReused() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger tokens = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch oldRequest = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            if (chain.request().url().encodedPath().contains("token/get")) {
                tokens.incrementAndGet();
                return QishuiClientTests.response(chain,
                        "{\"err_no\":0,\"client_token\":\"same-token\",\"expires_time\":4102444800}");
            }
            int request = requests.incrementAndGet();
            if (request == 1) {
                oldRequest.countDown();
                try { assertTrue(releaseOld.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
            return QishuiClientTests.response(chain, request <= 2
                    ? "{\"err_no\":28001003}" : "{\"err_no\":0,\"data\":{\"ok\":true}}");
        }).build();
        QishuiMusicClient client = new QishuiMusicClient(new QishuiHttpClient(http, mapper), mapper);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> delayed = pool.submit(() -> assertThrows(MusicBusinessException.class,
                    () -> client.post("/test/", mapper.createObjectNode())));
            assertTrue(oldRequest.await(5, TimeUnit.SECONDS));
            assertThrows(MusicBusinessException.class, () -> client.post("/test/", mapper.createObjectNode()));
            assertTrue(client.post("/test/", mapper.createObjectNode()).path("ok").asBoolean());
            releaseOld.countDown();
            delayed.get(5, TimeUnit.SECONDS);
            assertTrue(client.post("/test/", mapper.createObjectNode()).path("ok").asBoolean());
            assertEquals(2, tokens.get(), "late responses must not evict the new token generation");
        } finally { releaseOld.countDown(); pool.shutdownNow(); }
    }
}
