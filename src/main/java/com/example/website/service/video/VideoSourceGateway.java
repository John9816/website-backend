package com.example.website.service.video;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import com.example.website.config.AppProperties.VideoSources.WarehouseConfig;
import com.example.website.dto.video.VideoCategoryView;
import com.example.website.dto.video.VideoDetailView;
import com.example.website.dto.video.VideoEpisodeView;
import com.example.website.dto.video.VideoFilterView;
import com.example.website.dto.video.VideoItemView;
import com.example.website.dto.video.VideoLineView;
import com.example.website.dto.video.VideoPlayView;
import com.example.website.dto.video.VideoSearchPageView;
import com.example.website.dto.video.VideoSourceCapability;
import com.example.website.dto.video.VideoSourceKind;
import com.example.website.dto.video.VideoSourceView;
import com.example.website.dto.video.VideoTagView;
import com.example.website.service.VideoSourceCatalog;
import com.example.website.service.PerformanceSingleFlight;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Source-aware facade for registered pure-Java video providers. This service
 * handles registration, capability checks, normalized DTOs and safe media URLs.
 */
@Service
public class VideoSourceGateway {
    private static final int MAX_PAGE = 1000;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> OPERATIONS = new LinkedHashSet<>(
            Arrays.asList("home", "category", "detail", "search", "play"));

    private final VideoSourceCatalog catalog;
    private final JavaVideoProviderExecutor executor;
    private final OkHttpClient configClient;
    private final ObjectMapper mapper;
    private final TvboxConfigParser parser;
    private final Cache<String, TvboxConfig> configCache;
    private final List<VideoSourceView> fixedSources;
    private final PerformanceSingleFlight singleFlight;

    public VideoSourceGateway(AppProperties properties,
                              VideoSourceCatalog catalog,
                              OkHttpClient sharedClient,
                              ObjectMapper mapper) {
        this(properties, catalog, sharedClient, mapper, new PerformanceSingleFlight());
    }

    @Autowired
    public VideoSourceGateway(AppProperties properties,
                              VideoSourceCatalog catalog,
                              OkHttpClient sharedClient,
                              ObjectMapper mapper,
                              PerformanceSingleFlight singleFlight) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.parser = new TvboxConfigParser(mapper,
                Math.max(1024, properties.getVideoSources().getProviderConfigMaxBytes()));
        this.configCache = Caffeine.newBuilder()
                .maximumSize(32)
                .expireAfterWrite(Math.max(1, properties.getVideoSources().getProviderConfigCacheMinutes()),
                        TimeUnit.MINUTES)
                .build();
        this.configClient = sharedClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.executor = new JavaVideoProviderExecutor(sharedClient, mapper);
        this.fixedSources = null;
        this.singleFlight = singleFlight;
    }

    /** Constructor used by focused unit tests and embedded callers. */
    public VideoSourceGateway(VideoSourceCatalog catalog,
                              JavaVideoProviderExecutor executor,
                              List<VideoSourceView> fixedSources) {
        this.catalog = catalog;
        this.executor = executor;
        this.configClient = null;
        this.mapper = new ObjectMapper();
        this.parser = new TvboxConfigParser(mapper, 512 * 1024);
        this.configCache = Caffeine.newBuilder().maximumSize(1).build();
        this.fixedSources = fixedSources == null ? null : Collections.unmodifiableList(new ArrayList<>(fixedSources));
        this.singleFlight = new PerformanceSingleFlight();
    }

    public List<VideoSourceView> sources() {
        if (fixedSources != null) {
            List<VideoSourceView> enabled = new ArrayList<>();
            for (VideoSourceView source : fixedSources) {
                if (source.isEnabled()) enabled.add(source);
            }
            return Collections.unmodifiableList(enabled);
        }
        List<VideoSourceView> result = new ArrayList<>();
        for (WarehouseConfig warehouse : catalog.enabledWarehouses()) {
            if (warehouse.getKind() == VideoSourceKind.NCAT21_HTML) {
                String id = catalog.composeSourceId(warehouse.getId(), warehouse.getDefaultSiteKey());
                result.add(new VideoSourceView(id, warehouse.getName(), warehouse.getId(), warehouse.getName(),
                        warehouse.getDefaultSiteKey(), warehouse.getKind(), VideoSourceCapability.CMS_HTTP,
                        true, id.equals(catalog.defaultSourceId()), "ncat21 HTML 兼容源", warehouse.getSortOrder()));
                continue;
            }
            TvboxConfig config;
            try {
                config = loadConfig(warehouse);
            } catch (RuntimeException ex) {
                String id = catalog.composeSourceId(warehouse.getId(), warehouse.getDefaultSiteKey());
                result.add(new VideoSourceView(id, warehouse.getName(), warehouse.getId(), warehouse.getName(),
                        warehouse.getDefaultSiteKey(), warehouse.getKind(), VideoSourceCapability.UNSUPPORTED,
                        true, id.equals(catalog.defaultSourceId()), "源配置暂时不可用", warehouse.getSortOrder()));
                continue;
            }
            for (TvboxSite site : config.getSites()) {
                String id = catalog.composeSourceId(warehouse.getId(), site.getKey());
                boolean javaSupported = JavaVideoProviderExecutor.supports(site);
                VideoSourceCapability capability = javaSupported
                        ? site.getCapability() : VideoSourceCapability.UNSUPPORTED;
                String reason = javaSupported ? site.getReason()
                        : site.getCapability() == VideoSourceCapability.ANDROID_DEX
                        ? "该站点依赖 Android classes.dex Provider"
                        : "该站点没有纯 Java Provider 实现";
                result.add(new VideoSourceView(id, site.getName(), warehouse.getId(), warehouse.getName(),
                        site.getKey(), warehouse.getKind(), capability, true,
                        id.equals(catalog.defaultSourceId()), reason, warehouse.getSortOrder()));
            }
        }
        result.sort((a, b) -> {
            int order = Integer.compare(a.getSortOrder(), b.getSortOrder());
            return order != 0 ? order : a.getName().compareToIgnoreCase(b.getName());
        });
        return Collections.unmodifiableList(result);
    }

    public List<VideoCategoryView> categories(String sourceId) {
        Map<String, Object> data = execute(sourceId, "home", 1, 20, null, null,
                Collections.emptyMap(), null, null, null);
        return categoriesFrom(data);
    }

    public VideoFilterView filters(String sourceId) {
        Map<String, Object> data = execute(sourceId, "home", 1, 20, null, null,
                Collections.emptyMap(), null, null, null);
        return new VideoFilterView(strings(data.get("years")), strings(data.get("regions")),
                strings(data.get("genres")), strings(data.get("orders")));
    }

    public VideoSearchPageView discover(String sourceId, String category, String year, String region,
                                        String genre, String order, int page, int pageSize) {
        int safePage = clamp(page, 1, MAX_PAGE);
        int safeSize = clamp(pageSize, 1, MAX_PAGE_SIZE);
        Map<String, String> filters = new LinkedHashMap<>();
        put(filters, "year", year);
        put(filters, "region", region);
        put(filters, "genre", genre);
        put(filters, "order", order);
        String operation = text(category).isEmpty() ? "home" : "category";
        Map<String, Object> data = execute(sourceId, operation, safePage, safeSize, null, category,
                filters, null, null, null);
        return pageFrom(data, safePage, safeSize);
    }

    public VideoSearchPageView search(String sourceId, String keyword, String category,
                                      int page, int pageSize) {
        if (text(keyword).isEmpty() || keyword.trim().length() > 80) {
            throw new BusinessException(400, "搜索关键词不能为空或过长");
        }
        int safePage = clamp(page, 1, MAX_PAGE);
        int safeSize = clamp(pageSize, 1, MAX_PAGE_SIZE);
        Map<String, Object> data = execute(sourceId, "search", safePage, safeSize, keyword.trim(), category,
                Collections.emptyMap(), null, null, null);
        return pageFrom(data, safePage, safeSize);
    }

    public VideoDetailView detail(String sourceId, String videoId) {
        Map<String, Object> data = execute(sourceId, "detail", 1, 20, null, null,
                Collections.emptyMap(), videoId, null, null);
        return detailFrom(data, videoId);
    }

    public VideoPlayView play(String sourceId, String videoId, String episodeId, String lineId) {
        Map<String, Object> data = execute(sourceId, "play", 1, 20, null, null,
                Collections.emptyMap(), videoId, episodeId, lineId);
        return playFrom(data, videoId, episodeId, lineId);
    }

    public Map<String, Object> execute(String sourceId, String operation, int page, int pageSize,
                                       String keyword, String category, Map<String, String> filters,
                                       String videoId, String episodeId, String lineId) {
        if (!OPERATIONS.contains(operation)) throw new IllegalArgumentException("不支持的 Provider 操作");
        VideoSourceView source = requireSource(sourceId);
        if (source.getCapability() == VideoSourceCapability.UNSUPPORTED) {
            throw new VideoProviderException(source.getReason() == null
                    ? "该影视源当前不可执行" : source.getReason());
        }
        TvboxSite site = providerSite(source);
        return executor.execute(site, operation, clamp(page, 1, MAX_PAGE),
                clamp(pageSize, 1, MAX_PAGE_SIZE), keyword, category,
                filters == null ? Collections.emptyMap() : filters, videoId, episodeId, lineId);
    }

    private TvboxSite providerSite(VideoSourceView source) {
        if (fixedSources != null) return null;
        WarehouseConfig warehouse = catalog.requireWarehouse(source.getWarehouseId());
        TvboxSite site = loadConfig(warehouse).site(source.getSiteKey());
        if (site == null) throw new IllegalArgumentException("影视站点不存在: " + source.getId());
        return site;
    }

    private VideoSourceView requireSource(String sourceId) {
        String id = text(sourceId).isEmpty() ? catalog.defaultSourceId() : sourceId.trim();
        for (VideoSourceView source : sources()) {
            if (source.getId().equals(id)) {
                if (!source.isEnabled()) throw new IllegalArgumentException("影视源未启用: " + id);
                return source;
            }
        }
        throw new IllegalArgumentException("影视源未启用或不存在: " + id);
    }

    private TvboxConfig loadConfig(WarehouseConfig warehouse) {
        TvboxConfig cached = configCache.getIfPresent(warehouse.getId());
        if (cached != null) return cached;
        return singleFlight.execute("video.config:" + warehouse.getId(), () -> loadConfigUncoalesced(warehouse));
    }

    private TvboxConfig loadConfigUncoalesced(WarehouseConfig warehouse) {
        TvboxConfig cached = configCache.getIfPresent(warehouse.getId());
        if (cached != null) return cached;
        HttpUrl url = HttpUrl.parse(warehouse.getConfigUrl());
        if (url == null) throw new VideoProviderException("源配置地址无效");
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "DuckMusic ECS Provider Catalog")
                .build();
        try (Response response = configClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            byte[] body = response.body().bytes();
            if (body.length > 512 * 1024) throw new IOException("config too large");
            TvboxConfig config = parser.parse(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            configCache.put(warehouse.getId(), config);
            return config;
        } catch (IOException ex) {
            throw new VideoProviderException("源配置暂时不可用");
        }
    }

    private List<VideoCategoryView> categoriesFrom(Map<String, Object> data) {
        List<VideoCategoryView> result = new ArrayList<>();
        for (Map<String, Object> item : maps(first(data, "categories", "class", "list"))) {
            String id = firstText(item, "id", "value", "type_id", "typeId");
            String name = firstText(item, "name", "title", "label", "type_name", "typeName");
            if (!name.isEmpty()) result.add(new VideoCategoryView(id.isEmpty() ? name : id, name,
                    firstText(item, "url", "href")));
        }
        return result;
    }

    private VideoSearchPageView pageFrom(Map<String, Object> data, int page, int pageSize) {
        List<VideoItemView> result = new ArrayList<>();
        for (Map<String, Object> item : maps(first(data, "list", "items", "videos", "results"))) {
            String id = firstText(item, "id", "vod_id", "videoId");
            String title = firstText(item, "title", "name", "vod_name");
            if (!id.isEmpty() && !title.isEmpty()) result.add(new VideoItemView(id, title,
                    firstText(item, "posterUrl", "poster", "pic", "vod_pic"),
                    firstText(item, "detailUrl", "url", "vod_url"),
                    firstText(item, "status", "note", "vod_remarks"),
                    firstText(item, "description", "desc", "vod_content")));
        }
        int total = intValue(data.get("total"), result.size());
        boolean more = Boolean.TRUE.equals(data.get("hasMore")) || Boolean.TRUE.equals(data.get("has_more"));
        return new VideoSearchPageView(result, page, pageSize, total, more);
    }

    private VideoDetailView detailFrom(Map<String, Object> data, String fallbackId) {
        String id = firstText(data, "id", "videoId", "vod_id");
        if (id.isEmpty()) id = fallbackId == null ? "" : fallbackId;
        String title = firstText(data, "title", "name", "vod_name");
        List<VideoEpisodeView> episodes = new ArrayList<>();
        for (Map<String, Object> item : maps(first(data, "episodes", "playlists", "list"))) {
            String episodeId = firstText(item, "id", "episodeId", "url", "urlIndex");
            String name = firstText(item, "name", "title", "label");
            if (!name.isEmpty()) episodes.add(new VideoEpisodeView(episodeId, name, firstText(item, "url", "playUrl")));
        }
        List<VideoLineView> lines = new ArrayList<>();
        for (Map<String, Object> item : maps(first(data, "lines", "line"))) {
            String lineId = firstText(item, "id", "lineId", "key");
            String name = firstText(item, "name", "title", "label");
            if (!name.isEmpty()) lines.add(new VideoLineView(lineId, name, firstText(item, "description", "desc")));
        }
        List<VideoTagView> tags = new ArrayList<>();
        for (Object value : list(data.get("tags"))) {
            if (value instanceof Map) {
                Map<String, Object> item = map(value);
                String name = firstText(item, "name", "title", "label");
                if (!name.isEmpty()) tags.add(new VideoTagView(name, firstText(item, "url", "href")));
            } else if (!text(value).isEmpty()) tags.add(new VideoTagView(text(value), ""));
        }
        return new VideoDetailView(id, title, firstText(data, "posterUrl", "poster", "pic", "vod_pic"),
                firstText(data, "description", "desc", "vod_content"), tags, lines, episodes,
                firstText(data, "detailUrl", "url", "vod_url"));
    }

    private VideoPlayView playFrom(Map<String, Object> data, String videoId, String episodeId, String lineId) {
        String media = firstText(data, "mediaUrl", "url", "playUrl", "parseUrl");
        if (!media.isEmpty() && !isSafeMediaUrl(media)) media = "";
        String format = firstText(data, "format", "type");
        boolean playable = Boolean.TRUE.equals(data.get("playable")) && !media.isEmpty();
        if (!playable && !media.isEmpty()) playable = true;
        return new VideoPlayView(videoId, episodeId == null ? "" : episodeId, lineId == null ? "" : lineId,
                media.isEmpty() ? null : media, format.isEmpty() ? inferFormat(media) : format,
                firstText(data, "embedUrl", "embed"), firstText(data, "upstreamUrl", "pageUrl", "webUrl"),
                playable, firstText(data, "message", "error"));
    }

    private boolean isSafeMediaUrl(String value) {
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !"https".equalsIgnoreCase(url.scheme()) || !url.username().isEmpty() || !url.password().isEmpty()) return false;
        String host = url.host().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost")) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            return !(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress());
        } catch (UnknownHostException ignored) { return false; }
    }

    private String inferFormat(String media) {
        String lower = media.toLowerCase(Locale.ROOT);
        return lower.contains(".mp4") ? "mp4" : lower.contains(".mpd") ? "dash" : "hls";
    }

    private Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.get(key) != null) return map.get(key);
        return null;
    }

    private List<Map<String, Object>> maps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list(value)) if (item instanceof Map) result.add(map(item));
        return result;
    }

    private List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    private List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : list(value)) if (!text(item).isEmpty()) result.add(text(item));
        return result;
    }

    private String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) { String value = text(map.get(key)); if (!value.isEmpty()) return value; }
        return "";
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(text(value)); } catch (NumberFormatException ignored) { return fallback; }
    }

    private int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private void put(Map<String, String> target, String key, String value) { if (!text(value).isEmpty()) target.put(key, value.trim()); }
}
