package com.example.website.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.website.common.BusinessException;
import com.example.website.config.PerformanceMetrics;
import com.example.website.config.AppProperties;
import com.example.website.dto.video.VideoCategoryView;
import com.example.website.dto.video.VideoDetailView;
import com.example.website.dto.video.VideoEpisodeView;
import com.example.website.dto.video.VideoFilterView;
import com.example.website.dto.video.VideoItemView;
import com.example.website.dto.video.VideoLineView;
import com.example.website.dto.video.VideoPlayView;
import com.example.website.dto.video.VideoSearchPageView;
import com.example.website.dto.video.VideoTagView;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/** Fixed, non-JavaScript parser for the public ncat21 HTML pages. */
@Slf4j
@Service
public class NcatVideoService {

    private static final String NCAT21_ROOT_HOST = "ncat21.com";
    private static final Pattern DETAIL_ID = Pattern.compile("/detail/([^./?#]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAY_ID = Pattern.compile("/play/([^/?#]+)-([^/?#]+)-([^./?#]+)(?:\\.html)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MEDIA_URL = Pattern.compile(
            "https?://[^\\\"'<>\\s]+\\.(?:m3u8|mp4|mpd)(?:\\?[^\\\"'<>\\s]+)?",
            Pattern.CASE_INSENSITIVE);
    private static final List<VideoCategoryView> CATEGORIES = Arrays.asList(
            new VideoCategoryView("1", "电影", "/channel/1.html"),
            new VideoCategoryView("2", "连续剧", "/channel/2.html"),
            new VideoCategoryView("3", "动漫", "/channel/3.html"),
            new VideoCategoryView("4", "综艺纪录", "/channel/4.html"),
            new VideoCategoryView("6", "短剧", "/channel/6.html"));
    private static final VideoFilterView FILTERS = new VideoFilterView(
            Arrays.asList("2026", "2025", "2024", "2023", "2022", "2021", "2020"),
            Arrays.asList("中国大陆", "香港", "台湾", "韩国", "日本", "美国", "英国"),
            Arrays.asList("剧情", "喜剧", "动作", "爱情", "科幻", "悬疑", "惊悚", "犯罪", "纪录片"),
            Arrays.asList("最新", "最热", "评分"));

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final Cache<String, Object> cache;
    private final PerformanceSingleFlight singleFlight;
    private final PerformanceMetrics metrics;

    public NcatVideoService(OkHttpClient sharedClient, AppProperties properties) {
        this(sharedClient, properties, new PerformanceSingleFlight(), new PerformanceMetrics());
    }

    public NcatVideoService(OkHttpClient sharedClient, AppProperties properties,
                            PerformanceSingleFlight singleFlight) {
        this(sharedClient, properties, singleFlight, new PerformanceMetrics());
    }

    @Autowired
    public NcatVideoService(OkHttpClient sharedClient, AppProperties properties,
                            PerformanceSingleFlight singleFlight, PerformanceMetrics metrics) {
        this.baseUrl = normalizeBaseUrl(properties.getVideoSources().getNcat21BaseUrl());
        this.cache = Caffeine.newBuilder()
                .maximumSize(256)
                .expireAfterWrite(Math.max(1, properties.getVideoSources().getCacheMinutes()), TimeUnit.MINUTES)
                .build();
        this.httpClient = sharedClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(9, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.singleFlight = singleFlight;
        this.metrics = metrics;
    }

    public List<VideoCategoryView> categories() {
        List<VideoCategoryView> cached = cached("categories", List.class);
        if (cached != null) return cached;
        try {
            FetchedPage page = fetch(buildUrl("/"));
            List<VideoCategoryView> parsed = parseCategoriesHtml(page.body, page.url);
            List<VideoCategoryView> result = parsed.isEmpty() ? CATEGORIES : parsed;
            cache.put("categories", result);
            return result;
        } catch (BusinessException e) {
            log.warn("ncat21 categories unavailable: {}", e.getMessage());
            cache.put("categories", CATEGORIES);
            return CATEGORIES;
        }
    }

    public VideoFilterView filters() {
        return FILTERS;
    }

    public VideoSearchPageView discover(String category, String year, String region,
                                        String genre, String order, int page, int pageSize) {
        int safePage = clamp(page, 1, 1000);
        int safePageSize = clamp(pageSize, 1, 50);
        VideoCategoryView categoryView = categoryById(category);
        String path = categoryView == null ? "/label/new.html" : categoryView.getUrl();
        if (hasText(year) || hasText(region) || hasText(genre)) {
            path = "/show/" + cleanPathPart(categoryView == null ? "1" : categoryView.getId())
                    + "-" + cleanPathPart(genre)
                    + "-" + cleanPathPart(region)
                    + "-" + cleanPathPart(year)
                    + "-" + cleanPathPart(order)
                    + "-" + safePage + ".html";
        } else if (safePage > 1) {
            path = path.replace(".html", "-" + safePage + ".html");
        }
        String key = "discover:" + String.join("|", String.valueOf(category), String.valueOf(year),
                String.valueOf(region), String.valueOf(genre), String.valueOf(order),
                String.valueOf(safePage), String.valueOf(safePageSize));
        VideoSearchPageView cached = cached(key, VideoSearchPageView.class);
        if (cached != null) return cached;
        HttpUrl url = buildUrl(path);
        return singleFlight.execute(key, () -> {
            VideoSearchPageView result = parseSearchHtml(fetch(url).body, url.toString(), safePage, safePageSize);
            cache.put(key, result);
            return result;
        });
    }

    public VideoSearchPageView search(String keyword, String category, int page, int pageSize) {
        String normalized = requireKeyword(keyword);
        int safePage = clamp(page, 1, 1000);
        int safePageSize = clamp(pageSize, 1, 50);
        HttpUrl searchUrl = buildUrl("/search").newBuilder()
                .addQueryParameter("k", normalized)
                .addQueryParameter("page", String.valueOf(safePage))
                .build();
        if (categoryById(category) != null) {
            searchUrl = searchUrl.newBuilder().addQueryParameter("type", category).build();
        }
        final HttpUrl url = searchUrl;
        String key = "search:" + normalized + "|" + String.valueOf(category) + "|"
                + safePage + "|" + safePageSize;
        VideoSearchPageView cached = cached(key, VideoSearchPageView.class);
        if (cached != null) return cached;
        return singleFlight.execute(key, () -> {
            FetchedPage pageResult = fetch(url);
            VideoSearchPageView result = parseSearchHtml(pageResult.body, pageResult.url, safePage, safePageSize);
            cache.put(key, result);
            return result;
        });
    }

    public VideoDetailView detail(String id) {
        String safeId = requireId(id, "视频 id");
        VideoDetailView cached = cached("detail:" + safeId, VideoDetailView.class);
        if (cached != null) return cached;
        HttpUrl url = buildUrl("/detail/" + safeId + ".html");
        String key = "detail:" + safeId;
        return singleFlight.execute(key, () -> {
            FetchedPage page = fetch(url);
            VideoDetailView result = parseDetailHtml(page.body, page.url, safeId);
            cache.put(key, result);
            return result;
        });
    }

    public VideoPlayView play(String videoId, String episodeId, String lineId) {
        String safeVideo = requireId(videoId, "视频 id");
        String safeEpisode = requireId(episodeId == null ? "1" : episodeId, "选集 id");
        String safeLine = requireId(lineId == null ? "1" : lineId, "线路 id");
        String key = "play:" + safeVideo + "|" + safeEpisode + "|" + safeLine;
        VideoPlayView cached = cached(key, VideoPlayView.class);
        if (cached != null) return cached;
        HttpUrl url = buildUrl("/play/" + safeVideo + "-" + safeEpisode + "-" + safeLine + ".html");
        return singleFlight.execute(key, () -> {
            FetchedPage page = fetch(url);
            VideoPlayView result = parsePlayHtml(page.body, page.url, safeVideo, safeEpisode, safeLine);
            cache.put(key, result);
            return result;
        });
    }

    List<VideoCategoryView> parseCategoriesHtml(String html, String base) {
        Document doc = Jsoup.parse(html == null ? "" : html, base);
        Map<String, VideoCategoryView> result = new LinkedHashMap<>();
        for (Element link : doc.select("a[href*=/channel/][href]")) {
            String href = absoluteAllowedUrl(link.attr("href"), base);
            Matcher matcher = Pattern.compile("/channel/([^./?#]+)", Pattern.CASE_INSENSITIVE).matcher(href == null ? "" : href);
            if (!matcher.find()) continue;
            String name = clean(link.text());
            if (name.isEmpty()) continue;
            result.putIfAbsent(matcher.group(1), new VideoCategoryView(matcher.group(1), name, href));
        }
        return new ArrayList<>(result.values());
    }

    VideoSearchPageView parseSearchHtml(String html, String base, int page, int pageSize) {
        Document doc = Jsoup.parse(html == null ? "" : html, base);
        Map<String, VideoItemView> result = new LinkedHashMap<>();
        Elements cards = doc.select(".module-item, .module-item-box, .stui-vodlist__box, .myui-vodlist__box");
        for (Element card : cards) {
            addCard(result, card, base);
        }
        if (result.isEmpty()) {
            for (Element link : doc.select("a[href*=/detail/][href]")) {
                addCard(result, link.closest("li, article, .item, .module-item"), base);
                if (result.isEmpty()) addCard(result, link, base);
            }
        }
        List<VideoItemView> items = new ArrayList<>(result.values());
        int total = items.size();
        boolean hasMore = items.size() > pageSize || hasNextPage(doc);
        if (items.size() > pageSize) items = new ArrayList<>(items.subList(0, pageSize));
        return new VideoSearchPageView(items, page, pageSize, total, hasMore);
    }

    private boolean hasNextPage(Document doc) {
        for (Element link : doc.select("a[href], .page-link")) {
            String text = clean(link.text()).toLowerCase(Locale.ROOT);
            String rel = clean(link.attr("rel")).toLowerCase(Locale.ROOT);
            String aria = clean(link.attr("aria-label")).toLowerCase(Locale.ROOT);
            String title = clean(link.attr("title")).toLowerCase(Locale.ROOT);
            String classes = clean(link.className()).toLowerCase(Locale.ROOT);
            if (rel.contains("next") || classes.matches(".*(^|\\s)next(\\s|$).*")) return true;
            if (text.contains("下一页") || text.contains("下页") || text.equals("next")
                    || aria.contains("next") || title.contains("next")) return true;
        }
        return false;
    }

    VideoDetailView parseDetailHtml(String html, String base, String id) {
        Document doc = Jsoup.parse(html == null ? "" : html, base);
        String title = firstNonBlank(meta(doc, "og:title"), firstText(doc, "strong, h1, h2"));
        String poster = firstNonBlank(meta(doc, "og:image"), firstAttribute(doc, "img[alt], .lazyload", "data-src"), firstAttribute(doc, "img", "src"));
        poster = absoluteAllowedUrl(poster, base);
        String description = firstNonBlank(firstText(doc, ".detail-desc, .module-info-introduction, .data, .desc"), firstParagraph(doc));
        List<VideoTagView> tags = new ArrayList<>();
        Set<String> seenTags = new HashSet<>();
        for (Element link : doc.select("a[href*=/show/][href]")) {
            String name = clean(link.text());
            String url = absoluteAllowedUrl(link.attr("href"), base);
            if (!name.isEmpty() && url != null && seenTags.add(name)) tags.add(new VideoTagView(name, url));
        }
        List<VideoEpisodeView> episodes = new ArrayList<>();
        Set<String> seenEpisodes = new HashSet<>();
        for (Element link : doc.select("a[href*=/play/][href]")) {
            String url = absoluteAllowedUrl(link.attr("href"), base);
            Matcher matcher = PLAY_ID.matcher(url == null ? "" : url);
            String name = clean(link.text());
            if (url == null || !matcher.find() || name.isEmpty() || "立即播放".equals(name)
                    || !seenEpisodes.add(url)) continue;
            episodes.add(new VideoEpisodeView(matcher.group(2), name, url));
        }
        List<VideoLineView> lines = new ArrayList<>();
        Set<String> seenLines = new HashSet<>();
        for (Element link : doc.select("a[data-line], a[href='#'], .playlist a")) {
            String name = clean(firstNonBlank(link.attr("data-line"), link.text()));
            if (name.isEmpty() || !seenLines.add(name)) continue;
            lines.add(new VideoLineView(firstNonBlank(link.attr("data-line"), name), name, clean(link.attr("title"))));
        }
        String detailUrl = absoluteAllowedUrl(base, base);
        return new VideoDetailView(id, title, poster, description, tags, lines, episodes, detailUrl);
    }

    VideoPlayView parsePlayHtml(String html, String base, String videoId, String episodeId, String lineId) {
        Document doc = Jsoup.parse(html == null ? "" : html, base);
        String embed = absoluteAllowedUrl(firstAttribute(doc, "iframe[src], embed[src]", "src"), base);
        String media = firstMediaUrl(doc);
        if (media == null) {
            for (Element source : doc.select("video source[src], video[src]")) {
                String candidate = absoluteAllowedUrl(firstNonBlank(source.attr("src"), source.attr("data-src")), base);
                if (candidate != null && isMedia(candidate)) {
                    media = candidate;
                    break;
                }
            }
        }
        String format = media == null ? null : media.toLowerCase(Locale.ROOT).contains(".mp4") ? "mp4"
                : media.toLowerCase(Locale.ROOT).contains(".mpd") ? "dash" : "hls";
        boolean playable = media != null;
        String message = playable ? "ok" : embed == null ? "未找到可播放地址，请打开上游播放页" : "上游未暴露直链，请打开播放页";
        String upstream = absoluteAllowedUrl(base, base);
        return new VideoPlayView(videoId, episodeId, lineId, media, format, embed, upstream, playable, message);
    }

    private void addCard(Map<String, VideoItemView> result, Element card, String base) {
        if (card == null) return;
        Element link = card.selectFirst("a[href*=/detail/][href]");
        if (link == null && card.is("a[href*=/detail/][href]")) link = card;
        if (link == null) return;
        String url = absoluteAllowedUrl(link.attr("href"), base);
        Matcher matcher = DETAIL_ID.matcher(url == null ? "" : url);
        if (url == null || !matcher.find()) return;
        String title = firstNonBlank(
                clean(firstText(card, ".module-item-title, .module-item-name, .module-item-info h3, .module-item-info h4, .title")),
                clean(link.attr("title")), clean(link.text()));
        if (title.isEmpty()) return;
        String poster = absoluteAllowedUrl(firstAttribute(card, "img", "data-src"), base);
        if (poster == null) poster = absoluteAllowedUrl(firstAttribute(card, "img", "src"), base);
        String status = firstNonBlank(clean(firstText(card, ".module-item-text, .module-item-note, .pic-text, .status")), "");
        String description = clean(firstText(card, ".module-item-desc, .desc, p"));
        result.putIfAbsent(matcher.group(1), new VideoItemView(matcher.group(1), title, poster, url, status, description));
    }

    private String firstMediaUrl(Document doc) {
        for (Element script : doc.select("script")) {
            Matcher matcher = MEDIA_URL.matcher(script.data() + " " + script.html());
            while (matcher.find()) {
                String candidate = matcher.group();
                if (isAllowed(candidate) && isMedia(candidate)) return candidate;
            }
        }
        return null;
    }

    private boolean isMedia(String url) {
        return url != null && url.matches("(?i).+\\.(m3u8|mp4|mpd)(\\?.*)?$");
    }

    private VideoCategoryView categoryById(String id) {
        if (!hasText(id)) return null;
        for (VideoCategoryView category : CATEGORIES) if (category.getId().equals(id.trim())) return category;
        return null;
    }

    private FetchedPage fetch(HttpUrl url) {
        if (!isAllowed(url.toString())) throw new BusinessException(400, "不支持的 ncat21 地址");
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (DuckMusic ECS)")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new BusinessException(502, "ncat21 上游暂不可用");
            }
            return new FetchedPage(response.body().string(), response.request().url().toString());
        } catch (IOException e) {
            throw new BusinessException(502, "ncat21 上游连接失败");
        }
    }

    private HttpUrl buildUrl(String path) {
        HttpUrl base = HttpUrl.parse(baseUrl + (path.startsWith("/") ? path : "/" + path));
        if (base == null) throw new IllegalStateException("Invalid ncat21 base URL");
        return base;
    }

    private String absoluteAllowedUrl(String value, String base) {
        if (!hasText(value)) return null;
        HttpUrl baseHttp = HttpUrl.parse(base);
        HttpUrl resolved = baseHttp == null ? HttpUrl.parse(value.trim()) : baseHttp.resolve(value.trim());
        return resolved != null && isAllowed(resolved.toString()) ? resolved.toString() : null;
    }

    private boolean isAllowed(String value) {
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !"https".equalsIgnoreCase(url.scheme())) return false;
        String host = url.host().toLowerCase(Locale.ROOT);
        return host.equals(NCAT21_ROOT_HOST) || host.endsWith("." + NCAT21_ROOT_HOST);
    }

    private String requireKeyword(String value) {
        if (!hasText(value)) throw new BusinessException(400, "搜索关键词不能为空");
        String normalized = value.trim();
        if (normalized.length() > 80) throw new BusinessException(400, "搜索关键词过长");
        return normalized;
    }

    private String requireId(String value, String label) {
        if (!hasText(value) || !value.trim().matches("[A-Za-z0-9_-]{1,80}")) {
            throw new BusinessException(400, label + "无效");
        }
        return value.trim();
    }

    private String cleanPathPart(String value) {
        return hasText(value) ? value.trim().replaceAll("[^\\p{L}\\p{N}_-]", "") : "";
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeBaseUrl(String value) {
        String normalized = hasText(value) ? value.trim() : "https://www.ncat21.com";
        HttpUrl parsed = HttpUrl.parse(normalized);
        if (parsed == null || !"https".equalsIgnoreCase(parsed.scheme())
                || parsed.username().length() > 0 || parsed.password().length() > 0
                || !(parsed.host().equalsIgnoreCase(NCAT21_ROOT_HOST)
                || parsed.host().toLowerCase(Locale.ROOT).endsWith("." + NCAT21_ROOT_HOST))) {
            throw new IllegalArgumentException("APP_VIDEO_NCAT21_BASE_URL must be an HTTPS ncat21.com host");
        }
        return normalized.replaceAll("/+$", "");
    }

    private String meta(Document doc, String property) {
        Element element = doc.selectFirst("meta[property='" + property + "'], meta[name='" + property + "']");
        return element == null ? "" : clean(element.attr("content"));
    }

    private String firstText(Element root, String selector) {
        if (root == null) return "";
        Element element = root.selectFirst(selector);
        return element == null ? "" : element.text();
    }

    private String firstText(Document root, String selector) {
        Element element = root.selectFirst(selector);
        return element == null ? "" : element.text();
    }

    private String firstParagraph(Document root) {
        for (Element paragraph : root.select("p")) {
            String value = clean(paragraph.text());
            if (value.length() >= 8) return value;
        }
        return "";
    }

    private String firstAttribute(Element root, String selector, String attribute) {
        if (root == null) return "";
        Element element = root.selectFirst(selector);
        return element == null ? "" : element.attr(attribute);
    }

    private String firstAttribute(Document root, String selector, String attribute) {
        Element element = root.selectFirst(selector);
        return element == null ? "" : element.attr(attribute);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (hasText(value)) return value.trim();
        return "";
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Class<T> type) {
        Object value = cache.getIfPresent(key);
        if (!type.isInstance(value)) {
            metrics.recordCacheMiss("video.ncat21");
            return null;
        }
        metrics.recordCacheHit("video.ncat21");
        return (T) value;
    }

    private static final class FetchedPage {
        private final String body;
        private final String url;

        private FetchedPage(String body, String url) {
            this.body = body;
            this.url = url;
        }
    }
}
