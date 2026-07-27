package com.example.website.service.content;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.example.website.service.content.ContentTextUtils.asString;
import static com.example.website.service.content.ContentTextUtils.defaultText;
import static com.example.website.service.content.ContentTextUtils.hasText;
import static com.example.website.service.content.ContentTextUtils.trimToNull;

/**
 * TrendPublish-style candidate layer: normalize hot-list rows, hard-deduplicate them against
 * recent article titles, and attach transparent editorial scores before any LLM decision runs.
 */
@Service
public class TopicCandidateService {

    public ScoredCandidates score(List<Map<String, Object>> rawCandidates, String category, List<String> recentTitles) {
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return new ScoredCandidates(Collections.<Map<String, Object>>emptyList(), Collections.<Map<String, Object>>emptyList());
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        List<Map<String, Object>> usable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rawCandidates.size(); i++) {
            Map<String, Object> candidate = rawCandidates.get(i);
            String title = trimToNull(asString(candidate.get("title")));
            if (title == null) {
                continue;
            }
            String normalized = normalize(title);
            if (!seen.add(normalized)) {
                Map<String, Object> duplicateCard = scoreCard(candidate, category, recentTitles, i);
                duplicateCard.put("duplicate", true);
                duplicateCard.put("usable", false);
                duplicateCard.put("rejectReason", "same-batch duplicate");
                cards.add(duplicateCard);
                continue;
            }
            Map<String, Object> card = scoreCard(candidate, category, recentTitles, i);
            cards.add(card);
            boolean duplicate = Boolean.TRUE.equals(card.get("duplicate"));
            int finalScore = parseInt(card.get("finalScore"), 0);
            if (!duplicate && finalScore >= 45) {
                Map<String, Object> enriched = new LinkedHashMap<>(candidate);
                enriched.put("editorialScore", finalScore);
                enriched.put("noveltyScore", card.get("noveltyScore"));
                enriched.put("categoryScore", card.get("categoryScore"));
                enriched.put("specificnessScore", card.get("specificnessScore"));
                enriched.put("scoreReason", card.get("reason"));
                usable.add(enriched);
            }
        }
        usable.sort((a, b) -> Integer.compare(parseInt(b.get("editorialScore"), 0), parseInt(a.get("editorialScore"), 0)));
        return new ScoredCandidates(usable, cards);
    }

    public boolean isDuplicateTopic(String candidate, List<String> recentTitles) {
        if (!hasText(candidate) || recentTitles == null || recentTitles.isEmpty()) {
            return false;
        }
        for (String recent : recentTitles) {
            if (similarity(candidate, recent) >= 0.68d) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> scoreCard(Map<String, Object> candidate, String category, List<String> recentTitles, int index) {
        String title = defaultText(asString(candidate.get("title")), "");
        int sourceScore = sourceScore(asString(candidate.get("source")), asString(candidate.get("sourceName")));
        int rankScore = rankScore(candidate.get("rank"), index);
        int categoryScore = categoryScore(candidate, category);
        int noveltyScore = noveltyScore(title, recentTitles);
        int specificnessScore = specificnessScore(title, asString(candidate.get("summary")));
        int finalScore = sourceScore + rankScore + categoryScore + noveltyScore + specificnessScore;
        boolean duplicate = noveltyScore <= 5;
        if (duplicate) {
            finalScore -= 60;
        }
        if (title.length() < 8 || title.length() > 80) {
            finalScore -= 8;
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", title);
        card.put("source", candidate.get("source"));
        card.put("sourceName", candidate.get("sourceName"));
        card.put("rank", candidate.get("rank"));
        card.put("url", candidate.get("url"));
        card.put("sourceScore", sourceScore);
        card.put("rankScore", rankScore);
        card.put("categoryScore", categoryScore);
        card.put("noveltyScore", noveltyScore);
        card.put("specificnessScore", specificnessScore);
        card.put("finalScore", finalScore);
        card.put("duplicate", duplicate);
        card.put("usable", !duplicate && finalScore >= 45);
        card.put("reason", reason(sourceScore, rankScore, categoryScore, noveltyScore, specificnessScore, duplicate));
        return card;
    }

    private int sourceScore(String source, String sourceName) {
        String text = defaultText(source, "") + " " + defaultText(sourceName, "");
        if (text.contains("36kr") || text.contains("36\u6c2a") || text.contains("ithome") || text.contains("IT\u4e4b\u5bb6")) {
            return 18;
        }
        if (text.contains("zhihu") || text.contains("\u77e5\u4e4e") || text.contains("baidu") || text.contains("\u767e\u5ea6")) {
            return 14;
        }
        if (text.contains("weibo") || text.contains("\u5fae\u535a") || text.contains("toutiao") || text.contains("\u5934\u6761")) {
            return 10;
        }
        if (text.contains("fallback")) {
            return 4;
        }
        return 8;
    }

    private int rankScore(Object value, int index) {
        int rank = parseInt(value, index + 1);
        if (rank <= 1) {
            return 20;
        }
        if (rank <= 3) {
            return 17;
        }
        if (rank <= 6) {
            return 13;
        }
        if (rank <= 10) {
            return 9;
        }
        return 5;
    }

    private int categoryScore(Map<String, Object> candidate, String category) {
        String text = (defaultText(asString(candidate.get("title")), "") + " "
                + defaultText(asString(candidate.get("summary")), "") + " "
                + defaultText(asString(candidate.get("hot")), "")).toLowerCase();
        int score = 8;
        for (String keyword : hotKeywords(category)) {
            if (text.contains(keyword.toLowerCase())) {
                score += 5;
            }
        }
        return Math.min(score, 24);
    }

    private int noveltyScore(String title, List<String> recentTitles) {
        if (recentTitles == null || recentTitles.isEmpty()) {
            return 22;
        }
        double max = 0d;
        for (String recent : recentTitles) {
            max = Math.max(max, similarity(title, recent));
        }
        if (max >= 0.68d) {
            return 0;
        }
        if (max >= 0.52d) {
            return 8;
        }
        if (max >= 0.38d) {
            return 14;
        }
        return 22;
    }

    private int specificnessScore(String title, String summary) {
        int score = 8;
        String text = defaultText(title, "") + " " + defaultText(summary, "");
        if (text.matches(".*[A-Za-z0-9].*")) {
            score += 4;
        }
        if (text.matches(".*(AI|\u5927\u6a21\u578b|\u82af\u7247|\u624b\u673a|\u5e73\u53f0|\u653f\u7b56|\u5c31\u4e1a|\u62db\u8058|\u697c\u5e02|\u94f6\u884c|\u516c\u53f8|\u4ea7\u54c1|\u5e94\u7528|\u6559\u80b2|\u804c\u573a|\u8d22\u7ecf|\u91d1\u878d|\u4e92\u8054\u7f51|\u7535\u5546|\u6d88\u8d39).*")) {
            score += 6;
        }
        if (title.contains("\u4eca\u5929") && title.contains("\u4fe1\u606f\u5dee")) {
            score -= 6;
        }
        if (hasText(summary)) {
            score += 4;
        }
        return Math.max(0, Math.min(score, 18));
    }

    private String reason(int sourceScore, int rankScore, int categoryScore, int noveltyScore, int specificnessScore, boolean duplicate) {
        if (duplicate) {
            return "duplicate with recent titles; blocked by hard dedup";
        }
        return "source=" + sourceScore + ", rank=" + rankScore + ", category=" + categoryScore
                + ", novelty=" + noveltyScore + ", specificness=" + specificnessScore;
    }

    private double similarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (!hasText(a) || !hasText(b)) {
            return 0d;
        }
        if (a.equals(b)) {
            return 1d;
        }
        if ((a.length() >= 12 && b.contains(a)) || (b.length() >= 12 && a.contains(b))) {
            return 0.9d;
        }
        Set<String> gramsA = grams(a);
        Set<String> gramsB = grams(b);
        if (gramsA.isEmpty() || gramsB.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(gramsA);
        intersection.retainAll(gramsB);
        Set<String> union = new HashSet<>(gramsA);
        union.addAll(gramsB);
        return union.isEmpty() ? 0d : (double) intersection.size() / (double) union.size();
    }

    private Set<String> grams(String value) {
        if (value.length() <= 2) {
            return Collections.singleton(value);
        }
        Set<String> result = new HashSet<>();
        for (int i = 0; i < value.length() - 1; i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private String normalize(String value) {
        return defaultText(value, "").toLowerCase()
                .replaceAll("[\\p{Punct}\\s\\u3000-\\u303F\\uFF00-\\uFFEF]+", "");
    }

    private List<String> hotKeywords(String category) {
        String value = category == null ? "" : category;
        if (value.contains("\u6559\u80b2") || value.contains("\u804c\u573a")) {
            return Arrays.asList("\u6559\u80b2", "\u804c\u573a", "\u5c31\u4e1a", "\u8003\u7814", "\u9ad8\u8003", "\u5927\u5b66", "\u57f9\u8bad", "\u7b80\u5386", "\u62db\u8058", "\u88c1\u5458", "\u8f6c\u884c", "\u804c\u4e1a");
        }
        if (value.contains("\u8d22\u653f") || value.contains("\u91d1\u878d") || value.contains("\u8d22\u7ecf")) {
            return Arrays.asList("\u8d22\u7ecf", "\u91d1\u878d", "\u80a1\u5e02", "\u57fa\u91d1", "\u94f6\u884c", "\u697c\u5e02", "\u623f\u8d37", "\u6d88\u8d39", "\u7ecf\u6d4e", "\u653f\u7b56", "\u516c\u53f8", "\u8d22\u62a5");
        }
        return Arrays.asList("\u79d1\u6280", "\u4e92\u8054\u7f51", "AI", "\u4eba\u5de5\u667a\u80fd", "\u5927\u6a21\u578b", "\u624b\u673a", "\u82af\u7247", "\u5e73\u53f0", "\u4ea7\u54c1", "\u516c\u53f8", "\u7535\u5546", "\u5e94\u7528");
    }

    private int parseInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = trimToNull(asString(value));
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.replaceAll("[^0-9-]", ""));
        } catch (Exception e) {
            return fallback;
        }
    }

    @Data
    public static class ScoredCandidates {
        private final List<Map<String, Object>> usableCandidates;
        private final List<Map<String, Object>> scoreCards;

        public List<Map<String, Object>> topUsable(int limit) {
            if (usableCandidates == null || usableCandidates.isEmpty()) {
                return Collections.emptyList();
            }
            return usableCandidates.stream().limit(Math.max(1, limit)).collect(Collectors.toList());
        }

        public Map<String, Object> best() {
            return usableCandidates == null || usableCandidates.isEmpty() ? null : usableCandidates.get(0);
        }
    }
}
