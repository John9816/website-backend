package com.example.website.service.content;

import com.example.website.config.OkHttpConfig;
import com.example.website.dto.content.ContentHotTopicsView;
import com.example.website.service.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.example.website.service.content.ContentTextUtils.asString;
import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.firstString;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.hashText;
import static com.example.website.service.content.ContentTextUtils.trimToNull;

/**
 * Hot-topic collection for the content factory: fetches public hot lists, extracts
 * candidate rows, ranks/filters them against the active column, and falls back to
 * local seed topics when public sources are unavailable.
 *
 * <p>Extracted verbatim from {@code ContentArticleService}. Category normalization is
 * applied by the caller before invoking {@link #hotTopics(int, String)}.
 */
@Service
@RequiredArgsConstructor
public class HotTopicService {

    private static final String NOWHOTS_API_BASE = "https://api.nowhots.com/";

    private final SysConfigService configService;
    private final ObjectMapper objectMapper;
    @Qualifier(OkHttpConfig.CLIENT_QUICK)
    private final OkHttpClient okHttpClient;

    public ContentHotTopicsView hotTopics(int limit, String normalizedCategory) {
        int perSourceLimit = Math.min(Math.max(limit, 1), 30);
        List<HotSource> hotSources = hotSources(normalizedCategory);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(Math.max(hotSources.size(), 1), 8));
        List<Map<String, Object>> items;
        try {
            List<CompletableFuture<List<Map<String, Object>>>> tasks = hotSources.stream()
                    .map(hotSource -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return fetchHotSource(hotSource, perSourceLimit);
                        } catch (Exception ignored) {
                            // Keep the page usable when a public hot-list source is temporarily unavailable.
                            return Collections.<Map<String, Object>>emptyList();
                        }
                    }, executor))
                    .collect(Collectors.toList());
            items = tasks.stream()
                    .flatMap(task -> task.join().stream())
                    .collect(Collectors.toList());
        } finally {
            executor.shutdownNow();
        }
        items = rankHotTopics(items, normalizedCategory, perSourceLimit);
        if (items.isEmpty()) {
            items = fallbackHotTopics(normalizedCategory);
        }
        List<Map<String, Object>> sources = hotSources.stream()
                .map(item -> source(item.getId(), item.getName()))
                .collect(Collectors.toList());
        return new ContentHotTopicsView(LocalDateTime.now(), sources, items);
    }

    private List<HotSource> hotSources(String category) {
        String configured = defaultText(config("content.hot.sources." + category), config("content.hot.sources"));
        if (!hasText(configured)) {
            return defaultHotSources(category);
        }
        List<HotSource> parsed = parseHotSourcesJson(configured);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        parsed = parseHotSourcesLines(configured);
        return parsed.isEmpty() ? defaultHotSources(category) : parsed;
    }

    private List<HotSource> defaultHotSources(String category) {
        String normalized = defaultText(category, "");
        List<HotSource> sources = new ArrayList<>();
        if (normalized.contains("教育") || normalized.contains("职场")) {
            sources.add(nowhotsSource("nowhots-zhihu", "即时热点 · 知乎", "zhihu"));
            sources.add(nowhotsSource("nowhots-weibo", "即时热点 · 微博", "weibo"));
        } else if (normalized.contains("财政") || normalized.contains("金融") || normalized.contains("财经")) {
            sources.add(nowhotsSource("nowhots-36kr", "即时热点 · 36氪", "36kr"));
            sources.add(nowhotsSource("nowhots-zhihu", "即时热点 · 知乎", "zhihu"));
        } else {
            sources.add(nowhotsSource("nowhots-ithome", "即时热点 · IT之家", "ithome"));
            sources.add(nowhotsSource("nowhots-36kr", "即时热点 · 36氪", "36kr"));
            sources.add(nowhotsSource("nowhots-zhihu", "即时热点 · 知乎", "zhihu"));
        }
        sources.add(new HotSource("baidu", "百度热榜", "https://top.baidu.com/api/board?platform=wise&tab=realtime"));
        sources.add(new HotSource("toutiao", "头条热榜", "https://www.toutiao.com/hot-event/hot-board/?origin=toutiao_pc"));
        return sources;
    }

    private HotSource nowhotsSource(String id, String name, String code) {
        return new HotSource(id, name, NOWHOTS_API_BASE + code);
    }

    private List<HotSource> parseHotSourcesJson(String configured) {
        Object value = readObject(configured);
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<?> rows = (List<?>) value;
        List<HotSource> sources = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            if (!(row instanceof Map)) {
                continue;
            }
            Map<?, ?> record = (Map<?, ?>) row;
            String url = firstString(record, "url", "href", "api");
            if (!hasText(url)) {
                continue;
            }
            String id = defaultText(firstString(record, "id", "source"), "source-" + (i + 1));
            String name = defaultText(firstString(record, "name", "label", "title"), id);
            sources.add(new HotSource(id, name, url));
        }
        return sources;
    }

    private List<HotSource> parseHotSourcesLines(String configured) {
        List<HotSource> sources = new ArrayList<>();
        String[] lines = configured.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = trimToNull(lines[i]);
            if (line == null) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            if (parts.length < 2 || !hasText(parts[1])) {
                continue;
            }
            String name = defaultText(parts[0], "Source " + (i + 1));
            sources.add(new HotSource("custom-" + (i + 1), name, parts[1].trim()));
        }
        return sources;
    }

    private List<Map<String, Object>> fetchHotSource(HotSource source, int limit) throws IOException {
        Request request = new Request.Builder()
                .url(source.getUrl())
                .header("Accept", "application/json")
                .header("User-Agent", "website-content-factory/1.0")
                .get()
                .build();
        OkHttpClient client = okHttpClient.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .writeTimeout(4, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return Collections.emptyList();
            }
            Object payload = objectMapper.readValue(response.body().string(), Object.class);
            List<?> rows = extractHotRows(payload);
            List<Map<String, Object>> topics = new ArrayList<>();
            int max = Math.min(limit, rows.size());
            for (int i = 0; i < max; i++) {
                Map<String, Object> topic = hotTopicView(rows.get(i), source, i + 1);
                if (topic != null) {
                    topics.add(topic);
                }
            }
            return topics;
        }
    }

    private List<?> extractHotRows(Object payload) {
        return extractHotRows(payload, 0);
    }

    private List<?> extractHotRows(Object payload, int depth) {
        if (payload == null || depth > 6) {
            return Collections.emptyList();
        }
        if (payload instanceof List) {
            List<?> list = (List<?>) payload;
            if (looksLikeHotRows(list)) {
                return list;
            }
            for (Object item : list) {
                List<?> nested = extractHotRows(item, depth + 1);
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
            return Collections.emptyList();
        }
        if (!(payload instanceof Map)) {
            return Collections.emptyList();
        }
        Map<?, ?> record = (Map<?, ?>) payload;
        for (String key : Arrays.asList("data", "items", "list", "rows", "result", "hotList", "news", "cards", "content")) {
            Object value = record.get(key);
            List<?> nested = extractHotRows(value, depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        for (Object value : record.values()) {
            List<?> nested = extractHotRows(value, depth + 1);
            if (!nested.isEmpty()) {
                return nested;
            }
        }
        return Collections.emptyList();
    }

    private boolean looksLikeHotRows(List<?> rows) {
        for (Object row : rows) {
            if (row instanceof Map && hasText(firstString((Map<?, ?>) row,
                    "title", "Title", "name", "Name", "word", "Word", "keyword", "query", "desc", "display_query"))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> hotTopicView(Object row, HotSource source, int rank) {
        if (!(row instanceof Map)) {
            return null;
        }
        Map<?, ?> record = (Map<?, ?>) row;
        String title = firstString(record, "title", "Title", "name", "Name", "word", "Word", "keyword", "query", "desc", "display_query");
        if (!hasText(title)) {
            return null;
        }
        Map<String, Object> topic = new LinkedHashMap<>();
        topic.put("id", source.getId() + ":" + rank + ":" + hashText(title).substring(0, 8));
        topic.put("source", source.getId());
        topic.put("sourceName", source.getName());
        topic.put("rank", parseRank(record.get("rank"), record.get("index"), rank));
        topic.put("title", title);
        topic.put("url", trimToNull(firstString(record, "url", "Url", "link", "mobileUrl", "pcUrl", "href")));
        topic.put("hot", trimToNull(firstString(record, "hot", "heat", "score", "hotValue", "HotValue", "views", "metrics", "Label")));
        topic.put("summary", trimToNull(firstString(record, "summary", "excerpt", "description", "desc", "Abstract")));
        topic.put("capturedAt", LocalDateTime.now().toString());
        return topic;
    }

    private int parseRank(Object rank, Object index, int fallback) {
        for (Object value : Arrays.asList(rank, index)) {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value != null) {
                try {
                    return Integer.parseInt(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // Try the next value.
                }
            }
        }
        return fallback;
    }

    private List<Map<String, Object>> rankHotTopics(List<Map<String, Object>> topics, String category, int limit) {
        List<Map<String, Object>> ranked = new ArrayList<>(topics);
        ranked.sort(Comparator
                .comparingInt((Map<String, Object> item) -> topicCategoryScore(item, category)).reversed()
                .thenComparingInt(item -> parseRank(item.get("rank"), null, 999)));
        return ranked.stream().limit(limit).collect(Collectors.toList());
    }

    private int topicCategoryScore(Map<String, Object> topic, String category) {
        String text = (defaultText(asString(topic.get("title")), "") + " "
                + defaultText(asString(topic.get("summary")), "") + " "
                + defaultText(asString(topic.get("hot")), "")).toLowerCase();
        int score = 0;
        for (String keyword : hotKeywords(category)) {
            if (text.contains(keyword.toLowerCase())) {
                score += 6;
            }
        }
        return score;
    }

    private List<String> hotKeywords(String category) {
        String value = category == null ? "" : category;
        if (value.contains("教育") || value.contains("职场")) {
            return Arrays.asList("教育", "职场", "就业", "考研", "高考", "大学", "培训", "简历", "招聘", "裁员", "转行", "职业");
        }
        if (value.contains("财政") || value.contains("金融") || value.contains("财经")) {
            return Arrays.asList("财经", "金融", "股市", "基金", "银行", "楼市", "房贷", "消费", "经济", "政策", "公司", "财报");
        }
        return Arrays.asList("科技", "互联网", "AI", "人工智能", "大模型", "手机", "芯片", "平台", "产品", "公司", "电商", "应用");
    }

    private List<Map<String, Object>> fallbackHotTopics(String category) {
        String normalized = category == null ? "" : category;
        List<String> titles;
        if (normalized.contains("教育") || normalized.contains("职场")) {
            titles = Arrays.asList(
                    "今天职场和教育里的新信息差，普通人该先看懂什么",
                    "年轻人选择工作和学习路径时，最容易忽略的成本",
                    "招聘、培训和转行热度背后，真正变化的是什么"
            );
        } else if (normalized.contains("财政") || normalized.contains("金融") || normalized.contains("财经")) {
            titles = Arrays.asList(
                    "今天财经热点背后的信息差，和普通人的钱包有什么关系",
                    "消费、楼市和市场情绪变化里，普通人该看哪几个信号",
                    "公司新闻和政策变化背后，哪些影响会传导到日常生活"
            );
        } else {
            titles = Arrays.asList(
                    "今天科技互联网热点背后的信息差，普通人别只看标题",
                    "AI 产品和平台变化背后，哪些机会和坑最容易被忽略",
                    "大厂动作、产品更新和行业变化里，真正值得写的是什么"
            );
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            Map<String, Object> topic = new LinkedHashMap<>();
            topic.put("id", "fallback:" + hashText(category + ":" + titles.get(i)).substring(0, 10));
            topic.put("source", "fallback");
            topic.put("sourceName", "本地兜底选题");
            topic.put("rank", i + 1);
            topic.put("title", titles.get(i));
            topic.put("url", null);
            topic.put("hot", "兜底选题");
            topic.put("summary", "公共热榜源暂时不可用时，用于保证内容工厂仍可继续选题和生成草稿。");
            topic.put("capturedAt", LocalDateTime.now().toString());
            result.add(topic);
        }
        return result;
    }

    private Map<String, Object> source(String id, String name) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", id);
        source.put("name", name);
        return source;
    }

    private Object readObject(String json) {
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String config(String key) {
        return configService.getValue(key).orElse("");
    }

    @Data
    @AllArgsConstructor
    private static class HotSource {
        private String id;
        private String name;
        private String url;
    }
}
