package com.example.website.module.music.application;

import com.example.website.common.MusicBusinessException;
import com.example.website.common.MusicErrorCode;
import com.example.website.module.music.api.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Ticker;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class QishuiReadPerformanceTests {
    private static final String MEDIA = "{\"err_no\":0,\"data\":{\"media_list\":[{\"track_entity\":{"
            + "\"base_info\":{\"id\":\"track\",\"name\":\"Original\"},"
            + "\"display_info\":{\"lyric_info\":{\"lyric_entity\":{\"type\":\"lrc\",\"content\":\"[00:01]hello\"}}}}}]}}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger mediaCalls = new AtomicInteger(), feedCalls = new AtomicInteger();
    private final AtomicBoolean rejectFeed = new AtomicBoolean();
    private final AtomicBoolean rejectMedia = new AtomicBoolean();
    private final AtomicReference<String> mediaReply = new AtomicReference<>(MEDIA);

    private QishuiMusicClient catalog() {
        return catalog(Ticker.systemTicker());
    }

    private QishuiMusicClient catalog(Ticker ticker) {
        return new QishuiMusicClient(new QishuiHttpClient(new OkHttpClient.Builder().addInterceptor(chain -> {
            String path = chain.request().url().encodedPath();
            if (path.endsWith("/token/get/"))
                return QishuiClientTests.response(chain,"{\"err_no\":0,\"client_token\":\"public-test-token\",\"expires_time\":4102444800}");
            if (path.endsWith("/media/mget/")) {
                mediaCalls.incrementAndGet(); return QishuiClientTests.response(chain,
                        rejectMedia.get() ? "{\"err_no\":0,\"data\":{\"media_list\":[]}}" : mediaReply.get());
            }
            assertTrue(path.endsWith("/feed/song-tab/"));
            feedCalls.incrementAndGet();
            return QishuiClientTests.response(chain,rejectFeed.get() ? "{\"err_no\":1001}" : QishuiFeedClientTests.PAGE);
        }).build(),mapper),mapper,ticker);
    }

    @Test void metadataAndLyricsShareOnePublicUpstreamResponse() {
        QishuiMusicClient catalog = catalog();
        SongSearchItem song = catalog.fetchSongInfo("track"); song.setName("changed by caller");
        assertEquals("[00:01]hello",catalog.fetchLyrics("track").getLineLyrics());
        assertEquals("Original",catalog.fetchSongInfo("track").getName());
        assertEquals(1,mediaCalls.get(),"info and lyrics must share /media/mget without any personal binding");
    }

    @Test void discoveryReusesAShortPublicSnapshotAndReturnsIndependentViews() {
        QishuiFeedClient feed = new QishuiFeedClient(catalog(),mapper);
        QishuiDiscoveryView first = feed.discovery();
        first.getList().get(0).setName("changed by caller"); first.getList().clear();
        for (int i=0;i<3;i++) {
            QishuiDiscoveryView next = feed.discovery();
            assertEquals(1,next.getList().size()); assertEquals("测试歌曲",next.getList().get(0).getName());
        }
        assertEquals(1,feedCalls.get());
    }

    @Test void discoveryFailuresAreNotCachedAndPersonalFeedNeverUsesPublicCache() {
        QishuiFeedClient feed = new QishuiFeedClient(catalog(),mapper);
        rejectFeed.set(true); assertThrows(MusicBusinessException.class,feed::discovery);
        rejectFeed.set(false); feed.discovery(); feed.discovery();
        assertEquals(2,feedCalls.get());
        QishuiCredential credential = new QishuiCredential();
        credential.setUserId("upstream-test-user");credential.setDeviceId("test-device");
        feed.songs(credential,"recommended",1,1,null); feed.songs(credential,"recommended",2,2,null);
        assertEquals(4,feedCalls.get(),"personal calls must each reach upstream");
    }

    @Test void simultaneousDiscoveryMissesLoadOnce() throws Exception {
        QishuiFeedClient feed = new QishuiFeedClient(catalog(),mapper);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CyclicBarrier start = new CyclicBarrier(8);
        try {
            List<Future<QishuiDiscoveryView>> work = new ArrayList<>();
            for(int i=0;i<8;i++) work.add(pool.submit(() -> {start.await();return feed.discovery();}));
            for(Future<QishuiDiscoveryView> item:work) assertEquals(1,item.get(5,TimeUnit.SECONDS).getList().size());
            assertEquals(1,feedCalls.get());
        } finally {pool.shutdownNow();}
    }

    @Test void expiredPublicEntriesReloadWithoutExtendingTheirLifetimeOnRead() {
        AtomicLong time = new AtomicLong();
        QishuiMusicClient catalog = catalog(time::get);
        QishuiFeedClient feed = new QishuiFeedClient(catalog,mapper,time::get);
        feed.discovery(); catalog.fetchSongInfo("track");
        time.set(TimeUnit.SECONDS.toNanos(29)); feed.discovery(); catalog.fetchLyrics("track");
        assertEquals(1,feedCalls.get()); assertEquals(1,mediaCalls.get());
        time.set(TimeUnit.SECONDS.toNanos(31)); feed.discovery();
        assertEquals(2,feedCalls.get());
        time.set(TimeUnit.SECONDS.toNanos(301)); catalog.fetchLyrics("track");
        assertEquals(1,mediaCalls.get(), "complete public lyrics should survive normal playlist listening");
        time.set(TimeUnit.HOURS.toNanos(6) + TimeUnit.SECONDS.toNanos(1)); catalog.fetchLyrics("track");
        assertEquals(2,mediaCalls.get());
    }

    @Test void missingLyricsExpireQuicklySoLaterAvailabilityCanRecover() {
        AtomicLong time = new AtomicLong();
        QishuiMusicClient catalog = catalog(time::get);
        mediaReply.set(MEDIA.replace("[00:01]hello", ""));
        assertNull(catalog.fetchLyrics("track").getLineLyrics());
        mediaReply.set(MEDIA);
        time.set(TimeUnit.SECONDS.toNanos(301));
        assertEquals("[00:01]hello", catalog.fetchLyrics("track").getLineLyrics());
        assertEquals(2, mediaCalls.get());
    }

    @Test void wrongMediaIdentityDoesNotPoisonTheCache() {
        QishuiMusicClient catalog = catalog();
        rejectMedia.set(true);
        assertEquals(MusicErrorCode.SONG_NOT_FOUND,
                assertThrows(MusicBusinessException.class,() -> catalog.fetchSongInfo("track")).getErrorCode());
        rejectMedia.set(false);
        assertEquals("Original",catalog.fetchSongInfo("track").getName());
        assertEquals("[00:01]hello",catalog.fetchLyrics("track").getLineLyrics());
        assertEquals(2,mediaCalls.get());
    }

    @Test void malformedLyricsEvictOnlyTheirMediaSnapshotSoRecoveryCanRetry() {
        QishuiMusicClient catalog = catalog();
        mediaReply.set(MEDIA.replace("\"type\":\"lrc\",\"content\":\"[00:01]hello\"",
                "\"type\":\"krc\",\"content\":\"[9223372036854775807,1]<1,1,0>x\""));
        assertThrows(MusicBusinessException.class,() -> catalog.fetchLyrics("track"));
        mediaReply.set(MEDIA);
        assertEquals("[00:01]hello",catalog.fetchLyrics("track").getLineLyrics());
        assertEquals(2,mediaCalls.get());
    }

    @Test void waitingReadersRespectTheirBudgetWithoutCancellingTheSharedLoad() throws Exception {
        for (boolean discovery : new boolean[]{true,false}) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger requests = new AtomicInteger();
            QishuiMusicClient catalog = new QishuiMusicClient(new QishuiHttpClient(
                    new OkHttpClient.Builder().addInterceptor(chain -> {
                        if (chain.request().url().encodedPath().endsWith("/token/get/"))
                            return QishuiClientTests.response(chain,"{\"err_no\":0,\"client_token\":\"test-token\",\"expires_time\":4102444800}");
                        requests.incrementAndGet(); entered.countDown();
                        try {assertTrue(release.await(3,TimeUnit.SECONDS));}
                        catch (InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError(e);}
                        return QishuiClientTests.response(chain,discovery ? QishuiFeedClientTests.PAGE : MEDIA);
                    }).build(),mapper),mapper);
            QishuiFeedClient feed = new QishuiFeedClient(catalog,mapper);
            Runnable read = () -> {if(discovery)feed.discovery();else catalog.fetchSongInfo("track");};
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<?> leader = pool.submit(read);
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS));
                assertTimeout(Duration.ofSeconds(2),() -> {
                    try (MusicRequestBudget.Scope ignored = MusicRequestBudget.start(50,TimeUnit.MILLISECONDS)) {
                        assertEquals(MusicErrorCode.UPSTREAM_TIMEOUT,
                                assertThrows(MusicBusinessException.class,read::run).getErrorCode());
                    }
                });
            } finally {release.countDown(); pool.shutdown();}
            leader.get(3,TimeUnit.SECONDS); read.run();
            assertEquals(1,requests.get());
        }
    }
}
