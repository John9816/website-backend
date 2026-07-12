package com.example.website.service.content;

import com.example.website.service.SysConfigService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.trimToNull;

/**
 * Evidence completion (证据补全), the TrendPublish step that grounds the article in real,
 * citable material before writing. Given the chosen topic it derives a couple of search
 * queries, collects snippets via {@link WebSearchService}, and packages them for two uses:
 *
 * <ul>
 *   <li>an evidence block injected into the writer prompt so the draft cites facts;</li>
 *   <li>a source list persisted with the article for the 证据链 / risk record.</li>
 * </ul>
 *
 * <p>Entirely optional. Disabled by {@code content.evidence.enabled=false} or when no search
 * key is configured; a search miss degrades to opinion-only writing with a risk note, never a
 * hard failure.
 */
@Service
@RequiredArgsConstructor
public class EvidenceService {

    public static final String CFG_ENABLED = "content.evidence.enabled";
    public static final String CFG_MAX_QUERIES = "content.evidence.maxQueries";
    public static final String CFG_PER_QUERY = "content.evidence.perQuery";

    private final SysConfigService configService;
    private final WebSearchService webSearchService;

    /**
     * Collect evidence for {@code topic}. Returns an empty (but non-null) result when evidence
     * is disabled, unconfigured, or no snippets come back.
     */
    public EvidenceResult collect(String topic, String angle, String category) {
        String subject = trimToNull(topic);
        if (subject == null || !enabled() || !webSearchService.configured()) {
            return EvidenceResult.empty();
        }
        int maxQueries = clamp(parseInt(config(CFG_MAX_QUERIES), 2), 1, 4);
        int perQuery = clamp(parseInt(config(CFG_PER_QUERY), 3), 1, 6);

        List<String> queries = buildQueries(subject, angle, category, maxQueries);
        List<WebSearchService.SearchResult> hits = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (String query : queries) {
            for (WebSearchService.SearchResult result : webSearchService.search(query, perQuery)) {
                hits.add(result);
                if (hasText(result.getUrl())) {
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("title", defaultText(result.getTitle(), result.getUrl()));
                    source.put("url", result.getUrl());
                    source.put("query", query);
                    sources.add(source);
                }
            }
        }
        if (hits.isEmpty()) {
            return EvidenceResult.empty();
        }
        return new EvidenceResult(hits, sources);
    }

    /**
     * Derive search queries from the topic. The topic itself is always the primary query; an
     * angle or category adds one focused follow-up so evidence covers the intended framing.
     */
    static List<String> buildQueries(String topic, String angle, String category, int maxQueries) {
        List<String> queries = new ArrayList<>();
        queries.add(topic.trim());
        if (hasText(angle) && queries.size() < maxQueries) {
            queries.add(topic.trim() + " " + angle.trim());
        }
        if (hasText(category) && queries.size() < maxQueries && !queries.contains(topic.trim() + " " + category.trim())) {
            queries.add(topic.trim() + " " + category.trim());
        }
        return queries.size() > maxQueries ? queries.subList(0, maxQueries) : queries;
    }

    private boolean enabled() {
        // Default on: if a key is present, evidence is worth using unless explicitly disabled.
        return !"false".equalsIgnoreCase(config(CFG_ENABLED));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int parseInt(String value, int fallback) {
        String text = trimToNull(value);
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String config(String key) {
        return configService.getValue(key).orElse("");
    }

    /**
     * Collected evidence. {@code hits} feed the writer prompt; {@code sources} are persisted as
     * the article's citable 证据链. Both empty when evidence was skipped.
     */
    @Data
    @AllArgsConstructor
    public static class EvidenceResult {
        private List<WebSearchService.SearchResult> hits;
        private List<Map<String, Object>> sources;

        public static EvidenceResult empty() {
            return new EvidenceResult(new ArrayList<>(), new ArrayList<>());
        }

        public boolean isEmpty() {
            return hits == null || hits.isEmpty();
        }

        /** Numbered evidence block for injection into the writer prompt. */
        public String toPromptBlock() {
            if (isEmpty()) {
                return "";
            }
            StringBuilder block = new StringBuilder();
            int index = 1;
            for (WebSearchService.SearchResult hit : hits) {
                String line = hit.toEvidenceLine();
                if (hasText(line)) {
                    block.append(index++).append(". ").append(line).append("\n");
                }
            }
            return block.toString();
        }
    }
}
