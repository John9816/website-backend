package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import com.example.website.dto.video.VideoDetailView;
import com.example.website.dto.video.VideoItemView;
import com.example.website.dto.video.VideoPlayView;
import com.example.website.dto.video.VideoSearchPageView;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NcatVideoServiceTest {

    @Test
    void parsesCategoriesAndSearchCards() {
        NcatVideoService service = service();
        String html = "<nav>"
                + "<a href='/channel/1.html'>电影</a>"
                + "<a href='/channel/2.html'>连续剧</a>"
                + "</nav><div class='module-item'>"
                + "<a href='/detail/42.html'><img data-src='/upload/poster.jpg'>"
                + "<div class='module-item-title'>消失的人</div>"
                + "<div class='module-item-text'>正片</div></a>"
                + "<div class='module-item-desc'>悬疑犯罪</div></div>";

        assertEquals(2, service.parseCategoriesHtml(html, "https://www.ncat21.com/").size());
        VideoSearchPageView page = service.parseSearchHtml(
                html, "https://www.ncat21.com/search?k=消失的人", 1, 20);
        assertEquals(1, page.getList().size());
        VideoItemView item = page.getList().get(0);
        assertEquals("42", item.getId());
        assertEquals("消失的人", item.getTitle());
        assertEquals("https://www.ncat21.com/upload/poster.jpg", item.getPosterUrl());
        assertEquals("正片", item.getStatus());
        assertTrue(item.getDetailUrl().endsWith("/detail/42.html"));
    }

    @Test
    void onlyReportsMoreResultsWhenTheUpstreamExposesANextPage() {
        String card = "<div class='module-item'><a href='/detail/42.html'>"
                + "<div class='module-item-title'>消失的人</div></a></div>";

        assertFalse(service().parseSearchHtml(card, "https://www.ncat21.com/", 1, 1).isHasMore());
        assertTrue(service().parseSearchHtml(card + "<a class='next' href='/channel/1-2.html'>下一页</a>",
                "https://www.ncat21.com/", 1, 1).isHasMore());
    }

    @Test
    void parsesDetailTagsAndPlaybackLines() {
        NcatVideoService service = service();
        String html = "<img alt='消失的人' src='/poster.jpg'><strong>消失的人</strong>"
                + "<a href='/show/1----2026--.html'>2026</a>"
                + "<a href='/show/1-悬疑-----.html'>悬疑</a>"
                + "<p>一个寻常的清晨。</p>"
                + "<a href='/play/333692-41-3537870.html'>立即播放</a>"
                + "<a href='#' data-line='超清2'>超清2 秒播</a>"
                + "<a href='/play/333692-41-3537870.html'>1080P</a>";

        VideoDetailView detail = service.parseDetailHtml(
                html, "https://www.ncat21.com/detail/333692.html", "333692");
        assertEquals("333692", detail.getId());
        assertEquals("消失的人", detail.getTitle());
        assertEquals("https://www.ncat21.com/poster.jpg", detail.getPosterUrl());
        assertEquals("一个寻常的清晨。", detail.getDescription());
        assertEquals(2, detail.getTags().size());
        assertEquals("2026", detail.getTags().get(0).getName());
        assertEquals(1, detail.getEpisodes().size());
        assertEquals("1080P", detail.getEpisodes().get(0).getName());
        assertEquals(1, detail.getLines().size());
    }

    @Test
    void extractsPlayableMediaAndRejectsOffDomainUrls() {
        NcatVideoService service = service();
        String html = "<script>var player_aaaa={url:'https://cdn.ncat21.com/v/42.m3u8'};</script>"
                + "<iframe src='https://player.ncat21.com/embed/42'></iframe>";

        VideoPlayView playable = service.parsePlayHtml(
                html, "https://www.ncat21.com/play/42-1-1.html", "42", "1", "超清2");
        assertEquals("https://cdn.ncat21.com/v/42.m3u8", playable.getMediaUrl());
        assertEquals("hls", playable.getFormat());
        assertEquals("https://player.ncat21.com/embed/42", playable.getEmbedUrl());

        VideoPlayView blocked = service.parsePlayHtml(
                "<script>var player_aaaa={url:'https://evil.example/42.m3u8'};</script>",
                "https://www.ncat21.com/play/42-1-1.html", "42", "1", "超清2");
        assertEquals(null, blocked.getMediaUrl());
        assertFalse(blocked.isPlayable());
        assertTrue(blocked.getMessage().contains("未找到可播放"));
    }

    @Test
    void cachesRepeatedDetailRequestsWithinConfiguredWindow() {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    calls.incrementAndGet();
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(
                                    "<strong>消失的人</strong>",
                                    MediaType.parse("text/html; charset=utf-8")))
                            .build();
                })
                .build();
        NcatVideoService service = new NcatVideoService(client, new AppProperties());

        service.detail("42");
        service.detail("42");

        assertEquals(1, calls.get());
    }

    @Test
    void fetchesCategoriesSearchDetailAndPlayThroughLocalUpstreamFixture() {
        List<String> requestedPaths = new ArrayList<>();
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    String path = chain.request().url().encodedPath();
                    requestedPaths.add(path);
                    String body;
                    switch (path) {
                        case "/":
                            body = "<a href='/channel/1.html'>电影</a>";
                            break;
                        case "/search":
                            body = "<div class='module-item'><a href='/detail/42.html'>"
                                    + "<div class='module-item-title'>消失的人</div></a></div>";
                            break;
                        case "/detail/42.html":
                            body = "<strong>消失的人</strong><p>一个寻常的清晨。</p>";
                            break;
                        case "/play/42-1-1.html":
                            body = "<script>var player={url:'https://cdn.ncat21.com/v/42.m3u8'};</script>";
                            break;
                        default:
                            return response(chain, 404, "not found");
                    }
                    return response(chain, 200, body);
                })
                .build();
        NcatVideoService service = service(client);

        assertEquals("电影", service.categories().get(0).getName());
        assertEquals("42", service.search("消失的人", null, 1, 20).getList().get(0).getId());
        assertEquals("消失的人", service.detail("42").getTitle());
        VideoPlayView play = service.play("42", "1", "1");
        assertEquals("https://cdn.ncat21.com/v/42.m3u8", play.getMediaUrl());
        assertEquals("hls", play.getFormat());
        assertEquals(Arrays.asList("/", "/search", "/detail/42.html", "/play/42-1-1.html"), requestedPaths);
    }

    @Test
    void convertsUpstream502IntoBusinessException() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> response(chain, 502, "upstream unavailable"))
                .build();
        NcatVideoService service = service(client);

        BusinessException exception = assertThrows(BusinessException.class, () -> service.detail("42"));

        assertEquals(502, exception.getCode());
        assertEquals("ncat21 上游暂不可用", exception.getMessage());
    }

    @Test
    void rejectsNonNcat21ConfiguredBaseUrl() {
        AppProperties properties = new AppProperties();
        properties.getVideoSources().setNcat21BaseUrl("https://example.com");

        assertThrows(IllegalArgumentException.class,
                () -> new NcatVideoService(new OkHttpClient(), properties));
    }

    private Response response(Interceptor.Chain chain, int status, String body) throws IOException {
        return new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status >= 200 && status < 300 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.get("text/html; charset=utf-8")))
                .build();
    }

    private NcatVideoService service() {
        return service(new OkHttpClient());
    }

    private NcatVideoService service(OkHttpClient client) {
        return new NcatVideoService(client, new AppProperties());
    }
}
