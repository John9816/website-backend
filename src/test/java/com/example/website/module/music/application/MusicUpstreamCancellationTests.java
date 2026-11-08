package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.config.AppProperties;
import com.example.website.module.music.api.dto.MusicQuality;
import com.example.website.module.music.api.dto.MusicSource;
import com.example.website.module.music.api.dto.NeteasePlaybackQuality;
import com.example.website.module.music.api.dto.SongSearchItem;
import com.example.website.module.settings.application.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicUpstreamCancellationTests {
    enum Provider { CYAPI, TUNEFREE, CHKSZ, AUTH, URL_PROBE }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void cancelledSocketCallsDoNotPoisonNextHealthyRequest(Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            List<MusicErrorCode> errors = new ArrayList<>();
            for (int i = 0; i < 3; i++) errors.add(fixture.cancel("track-" + i));
            fixture.block = false;

            assertEquals("healthy", fixture.request.apply("track-2"),
                    "A cancelled loser must not open the circuit or cache a negative result");
            assertEquals(4, fixture.requests.get());
            for (MusicErrorCode error : errors) assertEquals(MusicErrorCode.UPSTREAM_TIMEOUT, error);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Provider.class, names = "URL_PROBE", mode = EnumSource.Mode.EXCLUDE)
    void cancelledHalfOpenProbeAllowsAnotherProbeImmediately(Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            AtomicLong clock = new AtomicLong(1_000);
            ThirdPartyCircuitBreaker breaker = new ThirdPartyCircuitBreaker("fixture", 3, 60_000, clock::get);
            for (int i = 0; i < 3; i++) breaker.recordFailure();
            ReflectionTestUtils.setField(fixture.client, "circuitBreaker", breaker);
            clock.addAndGet(60_000);

            fixture.cancel("probe");
            fixture.block = false;

            assertEquals("healthy", fixture.request.apply("probe"),
                    "Cancellation must release the half-open slot without restarting its cooldown");
            assertEquals(2, fixture.requests.get());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Provider.class, names = "URL_PROBE", mode = EnumSource.Mode.EXCLUDE)
    void cancellationBeforeSocketExecutionAlsoReleasesHalfOpenProbe(Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            AtomicLong clock = new AtomicLong(1_000);
            ThirdPartyCircuitBreaker breaker = new ThirdPartyCircuitBreaker("fixture", 3, 60_000, clock::get);
            for (int i = 0; i < 3; i++) breaker.recordFailure();
            ReflectionTestUtils.setField(fixture.client, "circuitBreaker", breaker);
            clock.addAndGet(60_000);
            MusicRequestBudget.CallGroup group = new MusicRequestBudget.CallGroup();
            group.cancel();
            try (MusicRequestBudget.Scope ignored = MusicRequestBudget.useDeadline(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(8), group)) {
                assertEquals(MusicErrorCode.UPSTREAM_TIMEOUT, failureCode(fixture.request, "probe"));
            }
            fixture.block = false;

            assertEquals("healthy", fixture.request.apply("probe"));
            assertEquals(1, fixture.requests.get());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Provider.class, names = "URL_PROBE", mode = EnumSource.Mode.EXCLUDE)
    void cancellationDoesNotErasePreviousRealFailures(Provider provider) throws Exception {
        try (Fixture fixture = new Fixture(provider)) {
            ThirdPartyCircuitBreaker breaker = new ThirdPartyCircuitBreaker("fixture");
            breaker.recordFailure();
            breaker.recordFailure();
            ReflectionTestUtils.setField(fixture.client, "circuitBreaker", breaker);

            fixture.cancel("cancelled");
            fixture.block = false;
            fixture.status = 503;
            assertNotNull(failureCode(fixture.request, "real-failure"));
            fixture.status = 200;

            assertNotNull(failureCode(fixture.request, "recovery"));
            assertEquals(2, fixture.requests.get(),
                    "The third real failure must open the circuit, while cancellation contributes no result");
        }
    }

    @Test
    void actualUpstreamTimeoutStillPopulatesFailureCache() throws Exception {
        try (Fixture fixture = new Fixture(Provider.CYAPI)) {
            Future<MusicErrorCode> timedOut = fixture.callers.submit(() -> {
                try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(150, TimeUnit.MILLISECONDS)) {
                    return failureCode(fixture.request, "timeout");
                }
            });
            CountDownLatch gate = fixture.started.poll(1, TimeUnit.SECONDS);
            assertNotNull(gate);
            try {
                assertEquals(MusicErrorCode.UPSTREAM_PLAY_FAILED, timedOut.get(1, TimeUnit.SECONDS));
            } finally { gate.countDown(); }
            fixture.block = false;
            assertEquals(MusicErrorCode.UPSTREAM_PLAY_FAILED, failureCode(fixture.request, "timeout"));
            assertEquals(1, fixture.requests.get(), "A real timeout must retain the existing backoff");
        }
    }

    private static MusicErrorCode failureCode(Function<String, String> request, String id) {
        try {
            request.apply(id);
            return null;
        } catch (MusicBusinessException ex) {
            return ex.getErrorCode();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService serverThreads = Executors.newCachedThreadPool();
        private final ExecutorService callers = Executors.newSingleThreadExecutor();
        private final BlockingQueue<CountDownLatch> started = new LinkedBlockingQueue<>();
        private final AtomicInteger requests = new AtomicInteger();
        private final OkHttpClient http;
        private final Object client;
        private final Function<String, String> request;
        private volatile boolean block = true;
        private volatile int status = 200;

        private Fixture(Provider provider) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(serverThreads);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                if (block) {
                    CountDownLatch gate = new CountDownLatch(1);
                    started.add(gate);
                    try { gate.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                }
                byte[] body = successBody(provider).getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                } catch (IOException ignored) {
                    // The cancelled caller has already closed this socket.
                } finally { exchange.close(); }
            });
            server.start();
            http = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                    .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                            .url("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                            .build())).build();
            ObjectMapper mapper = new ObjectMapper();
            switch (provider) {
                case CYAPI: {
                    SysConfigService config = mock(SysConfigService.class);
                    when(config.getValue(QqTextFallbackClient.CFG_API_KEY)).thenReturn(Optional.of("test-key"));
                    QqTextFallbackClient api = new QqTextFallbackClient(http, mapper, config);
                    client = api;
                    request = id -> {
                        SongSearchItem song = new SongSearchItem();
                        song.setName(id); song.setArtist("artist");
                        return api.fetchSongUrl(id, MusicQuality.K128, song).getPlayUrl();
                    };
                    break;
                }
                case TUNEFREE: {
                    TuneFreeAuthService auth = mock(TuneFreeAuthService.class);
                    when(auth.getToken()).thenReturn("test-token");
                    TuneFreePayClient api = new TuneFreePayClient(auth, http, mapper);
                    client = api;
                    request = id -> api.play(MusicSource.KUWO, id, MusicQuality.K128).getPlayUrl();
                    break;
                }
                case CHKSZ: {
                    AppProperties properties = new AppProperties();
                    properties.getMusic().getChksz().setEnabled(true);
                    properties.getMusic().getChksz().setApiKey("test-key");
                    properties.getMusic().getChksz().setBaseUrl("https://example.test");
                    ChkszNeteaseMusicClient api = new ChkszNeteaseMusicClient(properties, http, mapper);
                    client = api;
                    request = id -> api.resolve(id, NeteasePlaybackQuality.STANDARD).getName();
                    break;
                }
                case AUTH: {
                    SysConfigService config = mock(SysConfigService.class);
                    when(config.getValue(TuneFreeAuthService.CFG_ACCOUNT)).thenReturn(Optional.of("test-account"));
                    when(config.getValue(TuneFreeAuthService.CFG_PASSWORD)).thenReturn(Optional.of("test-password"));
                    TuneFreeAuthService api = new TuneFreeAuthService(config, http, mapper);
                    client = api;
                    request = id -> api.refresh(true);
                    break;
                }
                default: {
                    PlayUrlVerifier api = new PlayUrlVerifier(http);
                    client = api;
                    request = id -> api.isPlayable("https://example.test/" + id) ? "healthy" : "rejected";
                }
            }
        }

        private MusicErrorCode cancel(String id) throws Exception {
            MusicRequestBudget.CallGroup group = new MusicRequestBudget.CallGroup();
            Future<MusicErrorCode> future = callers.submit(() -> {
                try (MusicRequestBudget.Scope ignored = MusicRequestBudget.useDeadline(
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(8), group)) {
                    return failureCode(request, id);
                }
            });
            CountDownLatch gate = started.poll(1, TimeUnit.SECONDS);
            assertNotNull(gate, "The next request must reach the local upstream");
            try {
                group.cancel();
                return future.get(1, TimeUnit.SECONDS);
            } finally { gate.countDown(); }
        }

        @Override public void close() {
            callers.shutdownNow();
            server.stop(0);
            serverThreads.shutdownNow();
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdownNow();
        }
    }

    private static String successBody(Provider provider) {
        switch (provider) {
            case CYAPI: return "{\"url\":\"healthy\"}";
            case TUNEFREE: return "{\"data\":{\"data\":[{\"url\":\"healthy\"}]}}";
            case CHKSZ: return "{\"code\":200,\"data\":{\"url\":\"https://example.test/song.mp3\",\"name\":\"healthy\"}}";
            case AUTH: return "{\"code\":0,\"data\":{\"token\":\"healthy\"}}";
            default: return "audio";
        }
    }
}
