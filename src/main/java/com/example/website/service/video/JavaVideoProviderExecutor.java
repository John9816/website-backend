package com.example.website.service.video;

import com.example.website.dto.video.VideoSourceCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Executes supported providers directly in the Spring JVM. */
public class JavaVideoProviderExecutor {
    private static final String SHORT_DRAMA_HOST = "16409.kstore.vip";
    private static final String XIFAN_API = "https://xifan-api-cn.youlishipin.com";

    private final OkHttpClient client;
    private final ObjectMapper mapper;

    public JavaVideoProviderExecutor(OkHttpClient sharedClient, ObjectMapper mapper) {
        this.client = sharedClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        this.mapper = mapper;
    }

    public static boolean supports(TvboxSite site) {
        if (site == null) return false;
        if (site.getCapability() == VideoSourceCapability.CMS_HTTP) return true;
        HttpUrl api = HttpUrl.parse(site.getApi());
        return site.getCapability() == VideoSourceCapability.TVBOX_JS
                && api != null && SHORT_DRAMA_HOST.equalsIgnoreCase(api.host())
                && api.encodedPath().toLowerCase(Locale.ROOT).endsWith(".js")
                && (site.getKey().contains("短剧") || site.getName().contains("短剧"));
    }

    public Map<String, Object> execute(TvboxSite site,
                                       String operation,
                                       int page,
                                       int pageSize,
                                       String keyword,
                                       String category,
                                       Map<String, String> filters,
                                       String videoId,
                                       String episodeId,
                                       String lineId) {
        if (!supports(site)) {
            throw new VideoProviderException("该影视源没有纯 Java Provider 实现");
        }
        if (site.getCapability() == VideoSourceCapability.CMS_HTTP) {
            return executeCms(site, operation, page, keyword, category, filters, videoId);
        }
        return executeShortDrama(operation, page, pageSize, keyword, category, videoId, episodeId, lineId);
    }

    private Map<String, Object> executeCms(TvboxSite site, String operation, int page,
                                           String keyword, String category, Map<String, String> filters,
                                           String videoId) {
        HttpUrl base = requirePublicHttps(site.getApi());
        HttpUrl.Builder url = base.newBuilder().addQueryParameter("ac",
                "home".equals(operation) || "category".equals(operation) ? "list" : "detail");
        url.addQueryParameter("pg", String.valueOf(page));
        if ("search".equals(operation)) url.addQueryParameter("wd", trim(keyword));
        if ("category".equals(operation)) url.addQueryParameter("t", trim(category));
        if ("detail".equals(operation) || "play".equals(operation)) url.addQueryParameter("ids", trim(videoId));
        if (filters != null) {
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                if (!trim(entry.getValue()).isEmpty()) url.addQueryParameter(entry.getKey(), entry.getValue());
            }
        }
        JsonNode root = getJson(url.build(), site.getHeaders());
        return mapper.convertValue(root, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    private Map<String, Object> executeShortDrama(String operation, int page, int pageSize,
                                                   String keyword, String category, String videoId,
                                                   String episodeId, String lineId) {
        if ("home".equals(operation)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("categories", shortDramaCategories());
            result.put("list", fetchShortDramaList(page, pageSize));
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.put("hasMore", true);
            return result;
        }
        if ("category".equals(operation)) {
            return page(fetchShortDramaList(page, pageSize), page, pageSize);
        }
        if ("search".equals(operation)) {
            return page(searchShortDrama(keyword, pageSize), page, pageSize);
        }
        if ("detail".equals(operation)) return shortDramaDetail(videoId);
        if ("play".equals(operation)) return shortDramaPlay(episodeId, lineId);
        throw new IllegalArgumentException("不支持的 Java Provider 操作");
    }

    private List<Map<String, Object>> shortDramaCategories() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(pair("西饭", "西饭短剧"));
        return result;
    }

    private List<Map<String, Object>> fetchShortDramaList(int page, int pageSize) {
        HttpUrl url = HttpUrl.parse(XIFAN_API + "/xifan/drama/portalPage").newBuilder()
                .addQueryParameter("reqType", "aggregationPage")
                .addQueryParameter("offset", String.valueOf((page - 1) * pageSize))
                .addQueryParameter("categoryId", "")
                .build();
        return mapXifanList(getJson(url, Collections.emptyMap()), pageSize);
    }

    private List<Map<String, Object>> searchShortDrama(String keyword, int limit) {
        HttpUrl url = HttpUrl.parse(XIFAN_API + "/xifan/search/getSearchList").newBuilder()
                .addQueryParameter("keyword", trim(keyword))
                .addQueryParameter("offset", "0")
                .build();
        return mapXifanList(getJson(url, Collections.emptyMap()), limit);
    }

    private List<Map<String, Object>> mapXifanList(JsonNode root, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();
        JsonNode elements = root.path("result").path("elements");
        if (!elements.isArray()) return result;
        for (JsonNode element : elements) {
            JsonNode contents = element.path("contents");
            if (!contents.isArray()) continue;
            for (JsonNode content : contents) {
                JsonNode item = content.path("duanjuVo");
                String id = text(item, "duanjuId");
                String source = text(item, "source");
                String title = text(item, "title");
                if (id.isEmpty() || source.isEmpty() || title.isEmpty()) continue;
                Map<String, Object> video = new LinkedHashMap<>();
                video.put("id", "西饭@" + id + "#" + source);
                video.put("title", title);
                video.put("posterUrl", text(item, "coverImageUrl"));
                video.put("status", "共" + text(item, "total") + "集");
                video.put("description", text(item, "desc"));
                result.add(video);
                if (result.size() >= limit) return result;
            }
        }
        return result;
    }

    private Map<String, Object> shortDramaDetail(String compositeId) {
        int separator = trim(compositeId).indexOf('@');
        if (separator <= 0) throw new IllegalArgumentException("短剧视频 id 无效");
        String platform = compositeId.substring(0, separator);
        String[] parts = compositeId.substring(separator + 1).split("#", 2);
        if (!"西饭".equals(platform) || parts.length != 2) throw new IllegalArgumentException("短剧视频 id 无效");
        HttpUrl url = HttpUrl.parse(XIFAN_API + "/xifan/drama/getDuanjuInfo").newBuilder()
                .addQueryParameter("duanjuId", parts[0]).addQueryParameter("source", parts[1]).build();
        JsonNode root = getJson(url, Collections.emptyMap()).path("result");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", compositeId);
        result.put("title", text(root, "title"));
        result.put("posterUrl", text(root, "coverImageUrl"));
        result.put("description", text(root, "desc"));
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(pair("西饭", "西饭短剧"));
        result.put("lines", lines);
        List<Map<String, Object>> episodes = new ArrayList<>();
        JsonNode data = root.path("episodeList");
        if (data.isArray()) {
            int index = 1;
            for (JsonNode item : data) {
                String episode = text(item, "playUrl");
                if (episode.isEmpty()) continue;
                Map<String, Object> mapped = new LinkedHashMap<>();
                mapped.put("id", episode);
                mapped.put("name", "第" + item.path("index").asInt(index) + "集");
                mapped.put("url", episode);
                episodes.add(mapped);
                index++;
            }
        }
        result.put("episodes", episodes);
        return result;
    }

    private Map<String, Object> shortDramaPlay(String episodeId, String lineId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!"西饭".equals(trim(lineId))) throw new VideoProviderException("短剧播放线路不受支持");
        HttpUrl media = requirePublicHttps(episodeId);
        result.put("mediaUrl", media.toString());
        result.put("format", "mp4");
        result.put("playable", true);
        return result;
    }

    private Map<String, Object> page(List<Map<String, Object>> list, int page, int pageSize) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", list);
        result.put("page", page);
        result.put("pageSize", pageSize);
        result.put("total", list.size());
        result.put("hasMore", list.size() >= pageSize);
        return result;
    }

    private Map<String, Object> pair(String id, String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        return result;
    }

    private JsonNode getJson(HttpUrl url, Map<String, String> headers) {
        requirePublicHttps(url.toString());
        Request.Builder request = new Request.Builder().url(url)
                .header("User-Agent", "DuckMusic Java Provider")
                .header("Accept", "application/json");
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) request.header(entry.getKey(), entry.getValue());
        try (Response response = client.newCall(request.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            byte[] bytes = response.body().bytes();
            if (bytes.length > 512 * 1024) throw new IOException("response too large");
            return mapper.readTree(bytes);
        } catch (IOException ex) {
            throw new VideoProviderException("影视 Provider 上游暂不可用");
        }
    }

    private HttpUrl requirePublicHttps(String raw) {
        HttpUrl url = HttpUrl.parse(trim(raw));
        if (url == null || !"https".equalsIgnoreCase(url.scheme()) || !url.username().isEmpty() || !url.password().isEmpty()) {
            throw new VideoProviderException("影视 Provider 地址必须使用 HTTPS");
        }
        String host = url.host().toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.endsWith(".localhost")) throw new VideoProviderException("影视 Provider 地址非法");
        if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) {
            try {
                InetAddress address = InetAddress.getByName(host);
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()) throw new VideoProviderException("影视 Provider 地址非法");
            } catch (UnknownHostException ex) {
                throw new VideoProviderException("影视 Provider 地址非法");
            }
        }
        return url;
    }

    private String first(JsonNode node, String... names) {
        for (String name : names) { String value = text(node, name); if (!value.isEmpty()) return value; }
        return "";
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }
}
