package com.example.website.service.content;

import com.example.website.config.OkHttpConfig;
import com.example.website.service.SysConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.firstString;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.trimToNull;

/**
 * Live web search used by {@link EvidenceService} to back the article with real, citable
 * context. Modelled on TrendPublish's evidence/证据补全 step: the writer stays grounded in
 * public sources instead of free-associating.
 *
 * <p>Provider, endpoint and key all live in {@code sys_config} (never hardcoded), read at
 * call time so they can be rotated without a redeploy:
 *
 * <ul>
 *   <li>{@code content.search.provider} — {@code tavily} (default) or {@code serper}</li>
 *   <li>{@code content.search.baseUrl} — override the provider default endpoint</li>
 *   <li>{@code content.search.apiKey} — bearer/api key; blank disables search entirely</li>
 * </ul>
 *
 * <p>Search is best-effort: any failure (missing key, timeout, malformed body) yields an empty
 * result list so the pipeline degrades to opinion-only writing rather than failing the run.
 */
@Service
@RequiredArgsConstructor
public class WebSearchService {

    public static final String CFG_PROVIDER = "content.search.provider";
    public static final String CFG_BASE_URL = "content.search.baseUrl";
    public static final String CFG_API_KEY = "content.search.apiKey";

    private static final String TAVILY_DEFAULT_URL = "https://api.tavily.com/search";
    private static final String SERPER_DEFAULT_URL = "https://google.serper.dev/search";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final SysConfigService configService;
    private final ObjectMapper objectMapper;
    @Qualifier(OkHttpConfig.CLIENT_QUICK)
    private final OkHttpClient okHttpClient;

    /** True when a search key is configured; lets callers skip evidence prompts cheaply. */
    public boolean configured() {
        return hasText(config(CFG_API_KEY));
    }

    /**
     * Run a single query and return up to {@code maxResults} snippets. Never throws: any error
     * degrades to an empty list so evidence completion stays optional.
     */
    public List<SearchResult> search(String query, int maxResults) {
        String key = trimToNull(config(CFG_API_KEY));
        String q = trimToNull(query);
        if (key == null || q == null) {
            return new ArrayList<>();
        }
        int limit = Math.min(Math.max(maxResults, 1), 10);
        String provider = defaultText(config(CFG_PROVIDER), "tavily").toLowerCase();
        try {
            if ("serper".equals(provider)) {
                return searchSerper(q, key, limit);
            }
            return searchTavily(q, key, limit);
        } catch (Exception e) {
            // Best-effort: a search outage must not fail the article pipeline.
            return new ArrayList<>();
        }
    }

    private List<SearchResult> searchTavily(String query, String key, int limit) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("api_key", key);
        payload.put("query", query);
        payload.put("search_depth", "basic");
        payload.put("max_results", limit);
        payload.put("include_answer", false);

        Request request = new Request.Builder()
                .url(defaultText(config(CFG_BASE_URL), TAVILY_DEFAULT_URL))
                .header("Accept", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON))
                .build();
        Map<String, Object> body = execute(request);
        if (body == null) {
            return new ArrayList<>();
        }
        List<SearchResult> results = new ArrayList<>();
        Object rows = body.get("results");
        if (rows instanceof List) {
            for (Object row : (List<?>) rows) {
                if (!(row instanceof Map)) {
                    continue;
                }
                Map<?, ?> record = (Map<?, ?>) row;
                String title = firstString(record, "title", "name");
                String snippet = firstString(record, "content", "snippet", "description");
                String url = firstString(record, "url", "link");
                if (hasText(title) || hasText(snippet)) {
                    results.add(new SearchResult(defaultText(title, url), snippet, url));
                }
                if (results.size() >= limit) {
                    break;
                }
            }
        }
        return results;
    }

    private List<SearchResult> searchSerper(String query, String key, int limit) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("q", query);
        payload.put("num", limit);

        Request request = new Request.Builder()
                .url(defaultText(config(CFG_BASE_URL), SERPER_DEFAULT_URL))
                .header("Accept", "application/json")
                .header("X-API-KEY", key)
                .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON))
                .build();
        Map<String, Object> body = execute(request);
        if (body == null) {
            return new ArrayList<>();
        }
        List<SearchResult> results = new ArrayList<>();
        Object organic = body.get("organic");
        if (organic instanceof List) {
            for (Object row : (List<?>) organic) {
                if (!(row instanceof Map)) {
                    continue;
                }
                Map<?, ?> record = (Map<?, ?>) row;
                String title = firstString(record, "title");
                String snippet = firstString(record, "snippet", "description");
                String url = firstString(record, "link", "url");
                if (hasText(title) || hasText(snippet)) {
                    results.add(new SearchResult(defaultText(title, url), snippet, url));
                }
                if (results.size() >= limit) {
                    break;
                }
            }
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(Request request) throws Exception {
        // Tight timeouts: evidence is a nice-to-have, not worth stalling an article on.
        OkHttpClient client = okHttpClient.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            return objectMapper.readValue(response.body().string(), Map.class);
        }
    }

    private String config(String key) {
        return configService.getValue(key).orElse("");
    }

    /** A single search hit: a human title, a text snippet, and the source URL (any may be null). */
    @Data
    @AllArgsConstructor
    public static class SearchResult {
        private String title;
        private String snippet;
        private String url;

        public String toEvidenceLine() {
            StringBuilder line = new StringBuilder();
            if (hasText(title)) {
                line.append(title.trim());
            }
            if (hasText(snippet)) {
                if (line.length() > 0) {
                    line.append("：");
                }
                line.append(snippet.trim());
            }
            if (hasText(url)) {
                line.append("（来源：").append(url.trim()).append("）");
            }
            return line.toString();
        }
    }
}
