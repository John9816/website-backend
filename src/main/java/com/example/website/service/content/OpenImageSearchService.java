package com.example.website.service.content;

import com.example.website.config.OkHttpConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.example.website.service.content.ContentTextUtils.asString;
import static com.example.website.service.content.ContentTextUtils.hasText;

@Service
public class OpenImageSearchService {

    private static final String API = "https://commons.wikimedia.org/w/api.php";
    private static final List<Candidate> CURATED_COMMERCIAL_IMAGES = Collections.unmodifiableList(Arrays.asList(
            new Candidate(
                    "https://images.unsplash.com/photo-1518770660439-4636190af475?w=1024&q=80",
                    "https://unsplash.com/photos/turned-on-monitoring-screen-displaying-codes-OqtafYT5kTw",
                    "Technology infrastructure",
                    "NASA",
                    "Unsplash License",
                    "Photo by NASA on Unsplash. Unsplash License permits commercial use.",
                    "image/jpeg"
            ),
            new Candidate(
                    "https://images.unsplash.com/photo-1497366811353-6870744d04b2?w=1024&q=80",
                    "https://unsplash.com/photos/people-sitting-down-near-table-with-assorted-laptop-computers-wD1LRb9OeEo",
                    "Workplace discussion",
                    "Annie Spratt",
                    "Unsplash License",
                    "Photo by Annie Spratt on Unsplash. Unsplash License permits commercial use.",
                    "image/jpeg"
            ),
            new Candidate(
                    "https://images.unsplash.com/photo-1504384308090-c894fdcc538d?w=1024&q=80",
                    "https://unsplash.com/photos/person-using-macbook-pro-o4YXOgt8z3o",
                    "Team and laptop analysis",
                    "Campaign Creators",
                    "Unsplash License",
                    "Photo by Campaign Creators on Unsplash. Unsplash License permits commercial use.",
                    "image/jpeg"
            ),
            new Candidate(
                    "https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=1024&q=80",
                    "https://unsplash.com/photos/low-angle-photography-of-high-rise-building-tb4heMa-ZRo",
                    "Business district",
                    "Sean Pollock",
                    "Unsplash License",
                    "Photo by Sean Pollock on Unsplash. Unsplash License permits commercial use.",
                    "image/jpeg"
            ),
            new Candidate(
                    "https://images.unsplash.com/photo-1454165804606-c3d57bc86b40?w=1024&q=80",
                    "https://unsplash.com/photos/person-writing-on-white-paper-5fNmWej4tAA",
                    "Planning and evidence notes",
                    "Scott Graham",
                    "Unsplash License",
                    "Photo by Scott Graham on Unsplash. Unsplash License permits commercial use.",
                    "image/jpeg"
            )
    ));

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    public OpenImageSearchService(@Qualifier(OkHttpConfig.CLIENT_QUICK) OkHttpClient okHttpClient,
                                  ObjectMapper objectMapper) {
        this.okHttpClient = okHttpClient;
        this.objectMapper = objectMapper;
    }

    public List<Candidate> searchCommercial(String query, int limit) {
        if (!hasText(query) || limit <= 0) {
            return Collections.emptyList();
        }
        HttpUrl url = HttpUrl.parse(API).newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("generator", "search")
                .addQueryParameter("gsrsearch", "filetype:bitmap " + query.trim())
                .addQueryParameter("gsrnamespace", "6")
                .addQueryParameter("gsrlimit", String.valueOf(Math.max(limit * 3, 8)))
                .addQueryParameter("prop", "imageinfo")
                .addQueryParameter("iiprop", "url|mime|size|extmetadata")
                .addQueryParameter("iiurlwidth", "1024")
                .addQueryParameter("format", "json")
                .addQueryParameter("origin", "*")
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "website-content-factory/1.0")
                .get()
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return Collections.emptyList();
            }
            Map<String, Object> root = objectMapper.readValue(
                    response.body().string(), new TypeReference<Map<String, Object>>() {});
            Object queryNode = root.get("query");
            if (!(queryNode instanceof Map)) {
                return Collections.emptyList();
            }
            Object pagesNode = ((Map<?, ?>) queryNode).get("pages");
            if (!(pagesNode instanceof Map)) {
                return Collections.emptyList();
            }
            List<Candidate> result = new ArrayList<>();
            for (Object pageValue : ((Map<?, ?>) pagesNode).values()) {
                Candidate candidate = candidate(pageValue);
                if (candidate != null && commercialLicense(candidate.getLicense())) {
                    result.add(candidate);
                    if (result.size() >= limit) {
                        break;
                    }
                }
            }
            return result.isEmpty() ? curatedFallback(query, limit) : result;
        } catch (Exception ignored) {
            return curatedFallback(query, limit);
        }
    }

    private List<Candidate> curatedFallback(String query, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<Candidate> ordered = new ArrayList<>(CURATED_COMMERCIAL_IMAGES);
        int offset = Math.abs(String.valueOf(query).hashCode()) % ordered.size();
        Collections.rotate(ordered, -offset);
        return ordered.subList(0, Math.min(limit, ordered.size()));
    }

    private Candidate candidate(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<?, ?> page = (Map<?, ?>) value;
        Object infoNode = page.get("imageinfo");
        if (!(infoNode instanceof List) || ((List<?>) infoNode).isEmpty()
                || !(((List<?>) infoNode).get(0) instanceof Map)) {
            return null;
        }
        Map<?, ?> info = (Map<?, ?>) ((List<?>) infoNode).get(0);
        String imageUrl = asString(info.get("thumburl"));
        if (!hasText(imageUrl)) {
            imageUrl = asString(info.get("url"));
        }
        if (!hasText(imageUrl)) {
            return null;
        }
        Map<?, ?> metadata = info.get("extmetadata") instanceof Map
                ? (Map<?, ?>) info.get("extmetadata") : Collections.emptyMap();
        String license = metadataValue(metadata, "LicenseShortName");
        String author = stripHtml(metadataValue(metadata, "Artist"));
        String attribution = stripHtml(metadataValue(metadata, "Credit"));
        return new Candidate(
                imageUrl,
                asString(info.get("descriptionurl")),
                asString(page.get("title")),
                author,
                license,
                hasText(attribution) ? attribution : author,
                asString(info.get("mime"))
        );
    }

    private String metadataValue(Map<?, ?> metadata, String key) {
        Object node = metadata.get(key);
        return node instanceof Map ? asString(((Map<?, ?>) node).get("value")) : null;
    }

    boolean commercialLicense(String value) {
        if (!hasText(value)) {
            return false;
        }
        String license = value.toLowerCase();
        if (license.contains("noncommercial") || license.contains("-nc")) {
            return false;
        }
        return license.contains("cc0")
                || license.contains("public domain")
                || license.contains("cc by")
                || license.contains("cc-by");
    }

    private String stripHtml(String value) {
        return hasText(value) ? value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim() : null;
    }

    @Data
    @AllArgsConstructor
    public static class Candidate {
        private String imageUrl;
        private String pageUrl;
        private String title;
        private String author;
        private String license;
        private String attribution;
        private String mimeType;
    }
}
