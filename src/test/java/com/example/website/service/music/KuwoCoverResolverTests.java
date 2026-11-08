package com.example.website.service.music;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KuwoCoverResolverTests {

    @Test
    void oversizedCoverBodyStopsReadingAndDoesNotPoisonTheCache() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicLong bytesRead = new AtomicLong();
        AtomicBoolean bodyClosed = new AtomicBoolean();
        byte[] payload = new byte[64 * 1024];
        Arrays.fill(payload, (byte) 'a');
        byte[] prefix = "https://img1.kuwo.cn/".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, payload, 0, prefix.length);
        BufferedSource source = Okio.buffer(new ForwardingSource(new Buffer().write(payload)) {
            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                long read = super.read(sink, byteCount);
                if (read > 0) bytesRead.addAndGet(read);
                return read;
            }

            @Override
            public void close() throws IOException {
                bodyClosed.set(true);
                super.close();
            }
        });
        ResponseBody oversized = new ResponseBody() {
            @Override public MediaType contentType() { return MediaType.get("text/plain; charset=utf-8"); }
            @Override public long contentLength() { return -1; }
            @Override public BufferedSource source() { return source; }
        };
        OkHttpClient client = client(chain -> requests.incrementAndGet() == 1
                ? new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body(oversized).build()
                : response(chain, "https://img1.kuwo.cn/recovered.jpg"));
        try (KuwoCoverResolver covers = resolver(client)) {
            assertTrue(covers.resolve("42").get(1, TimeUnit.SECONDS) == null,
                    "A URL body exceeding 8 KiB must be rejected");
            assertTrue(bytesRead.get() <= 16 * 1024,
                    "The reader must stop after the limit and one buffered lookahead");
            assertTrue(bodyClosed.get());
            assertEquals("https://img1.kuwo.cn/recovered.jpg", covers.resolve("42").get(1, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
        }
    }

    @Test
    void coverMetricsRecordFiniteCacheAndUpstreamOutcomes() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = client(chain -> {
            if (requests.incrementAndGet() == 1) throw new IOException("temporary failure");
            return response(chain, "https://img1.kuwo.cn/sensitive-cover.jpg");
        });
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(registry);
        try (KuwoCoverResolver covers = resolver(client)) {
            assertNull(covers.resolve("sensitive-song-id").get(1, TimeUnit.SECONDS));
            covers.resolve("sensitive-song-id").get(1, TimeUnit.SECONDS);
            covers.resolve("sensitive-song-id").get(1, TimeUnit.SECONDS);

            assertNotNull(registry.find("music.requests").tags("source", "kuwo", "operation", "cover", "outcome", "success").counter());
            assertEquals(1, registry.get("music.requests").tags("source", "kuwo", "operation", "cover", "outcome", "failure").counter().count());
            assertEquals(1, registry.get("music.cache.requests").tags("source", "kuwo", "operation", "cover", "result", "hit").counter().count());
            assertTrue(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                    .noneMatch(tag -> tag.getValue().contains("sensitive")));
        } finally {
            Metrics.globalRegistry.remove(registry);
            registry.close();
        }
    }

    @Test
    void closingCompletesRunningAndQueuedLookups() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OkHttpClient client = client(chain -> {
            started.countDown();
            awaitUninterruptibly(release);
            return response(chain, "https://img1.kuwo.cn/cover.jpg");
        });
        KuwoCoverResolver covers = resolver(client);
        try {
            CompletableFuture<String> running = covers.resolve("running");
            assertTrue(started.await(1, TimeUnit.SECONDS));
            CompletableFuture<String> queued = covers.resolve("queued");
            covers.close();
            assertNull(running.get(100, TimeUnit.MILLISECONDS));
            assertNull(queued.get(100, TimeUnit.MILLISECONDS));
            assertNull(covers.resolve("after-close").get(100, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            covers.close();
        }
    }

    @Test
    void failedLookupCanRecoverOnTheNextRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = client(chain -> {
            if (requests.incrementAndGet() == 1) throw new IOException("temporary failure");
            return response(chain, "https://img1.kuwo.cn/recovered.jpg");
        });
        try (KuwoCoverResolver covers = resolver(client)) {
            assertNull(covers.resolve("42").get(1, TimeUnit.SECONDS));
            assertEquals("https://img1.kuwo.cn/recovered.jpg", covers.resolve("42").get(1, TimeUnit.SECONDS));
            assertEquals("https://img1.kuwo.cn/recovered.jpg", covers.resolve("42").get(1, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://img.kuwo.cn.example.com/a.jpg", "https://example.com/a.jpg", "https://img1.kuwo.cn/", "not a URL"})
    void unverifiedImagesAreNotRetained(String unverified) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = client(chain -> response(chain, requests.incrementAndGet() == 1
                ? unverified : "http://img4.kuwo.cn/verified.jpg"));
        try (KuwoCoverResolver covers = resolver(client)) {
            assertNull(covers.resolve("42").get(1, TimeUnit.SECONDS));
            assertEquals("http://img4.kuwo.cn/verified.jpg", covers.resolve("42").get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void completedCoverExpiresAfterItsRetentionPeriod() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = client(chain -> response(chain,
                "https://img1.kuwo.cn/cover-" + requests.incrementAndGet() + ".jpg"));
        try (KuwoCoverResolver covers = new KuwoCoverResolver(client, 10, Duration.ofSeconds(1),
                1, 1, Duration.ofSeconds(1), clock::get)) {
            assertEquals("https://img1.kuwo.cn/cover-1.jpg", covers.resolve("42").get(1, TimeUnit.SECONDS));
            assertEquals("https://img1.kuwo.cn/cover-1.jpg", covers.resolve("42").get(1, TimeUnit.SECONDS));
            clock.set(TimeUnit.SECONDS.toNanos(2));
            assertEquals("https://img1.kuwo.cn/cover-2.jpg", covers.resolve("42").get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void completedCacheEvictsCoversBeyondItsCapacity() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = client(chain -> {
            requests.incrementAndGet();
            return response(chain, "https://img1.kuwo.cn/" + chain.request().url().queryParameter("rid") + ".jpg");
        });
        try (KuwoCoverResolver covers = new KuwoCoverResolver(client, 1, Duration.ofHours(6),
                1, 1, Duration.ofSeconds(1), Ticker.systemTicker())) {
            covers.resolve("1").get(1, TimeUnit.SECONDS);
            covers.resolve("2").get(1, TimeUnit.SECONDS);
            assertEquals(2, requests.get());
            covers.resolve("1").get(1, TimeUnit.SECONDS);
            covers.resolve("2").get(1, TimeUnit.SECONDS);
            assertTrue(requests.get() > 2, "The one-entry cache cannot retain both covers");
        }
    }

    @Test
    void saturatedWorkRejectsImmediatelyAndQueuedCallsShareTheTotalDeadline() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        OkHttpClient client = client(chain -> {
            if (executed.incrementAndGet() == 1) {
                firstStarted.countDown();
                awaitUninterruptibly(releaseFirst);
            }
            return response(chain, "https://img1.kuwo.cn/cover.jpg");
        });
        try (KuwoCoverResolver covers = new KuwoCoverResolver(client, 10, Duration.ofHours(6),
                1, 1, Duration.ofMillis(250), Ticker.systemTicker())) {
            CompletableFuture<String> running = covers.resolve("running");
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            CompletableFuture<String> queued = covers.resolve("queued");
            assertNull(covers.resolve("rejected").get(100, TimeUnit.MILLISECONDS));
            assertNull(running.get(1, TimeUnit.SECONDS));
            assertNull(queued.get(1, TimeUnit.SECONDS));
            assertEquals(1, executed.get(), "A queued call must expire before getting a worker");

            CompletableFuture<String> recovered = covers.resolve("recovered");
            releaseFirst.countDown();
            assertEquals("https://img1.kuwo.cn/cover.jpg", recovered.get(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void aLateResponseCannotReplaceTheRetriedCover() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        OkHttpClient client = client(chain -> {
            if (requests.incrementAndGet() == 1) {
                firstStarted.countDown();
                awaitUninterruptibly(releaseFirst);
                return response(chain, "https://img1.kuwo.cn/stale.jpg");
            }
            return response(chain, "https://img1.kuwo.cn/current.jpg");
        });
        try (KuwoCoverResolver covers = new KuwoCoverResolver(client, 10, Duration.ofHours(6),
                1, 1, Duration.ofMillis(250), Ticker.systemTicker())) {
            CompletableFuture<String> expired = covers.resolve("42");
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertNull(expired.get(1, TimeUnit.SECONDS));
            CompletableFuture<String> retried = covers.resolve("42");
            releaseFirst.countDown();
            assertEquals("https://img1.kuwo.cn/current.jpg", retried.get(1, TimeUnit.SECONDS));
            assertEquals("https://img1.kuwo.cn/current.jpg", covers.resolve("42").get(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void callCreationFailureReleasesAdmissionForTheNextRequest() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient client = client(chain -> response(chain, "https://img1.kuwo.cn/cover.jpg"));
        Call.Factory calls = request -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporarily unavailable");
            return client.newCall(request);
        };
        try (KuwoCoverResolver covers = resolver(calls)) {
            assertNull(covers.resolve("42").get(1, TimeUnit.SECONDS));
            assertEquals("https://img1.kuwo.cn/cover.jpg", covers.resolve("42").get(1, TimeUnit.SECONDS));
        }
    }

    private static KuwoCoverResolver resolver(Call.Factory calls) {
        return new KuwoCoverResolver(calls, 10, Duration.ofHours(6), 1, 1,
                Duration.ofSeconds(1), Ticker.systemTicker());
    }

    private static OkHttpClient client(Interceptor interceptor) {
        return new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(interceptor).build();
    }

    private static Response response(Interceptor.Chain chain, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(ResponseBody.create(body, MediaType.get("text/plain"))).build();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) throws IOException {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (true) {
                try {
                    if (!latch.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        throw new IOException("fixture deadline");
                    }
                    return;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
