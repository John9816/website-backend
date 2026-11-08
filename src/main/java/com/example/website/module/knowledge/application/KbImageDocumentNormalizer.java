package com.example.website.module.knowledge.application;

import com.example.website.common.BusinessException;
import com.example.website.module.assets.domain.ImageAsset;
import com.example.website.module.assets.application.ImageStorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Hosts pasted image sources before a KB document becomes durable. */
@Service
@RequiredArgsConstructor
public class KbImageDocumentNormalizer {
    private static final int MAX_DISTINCT_IMAGES = 20;
    private static final String LEGACY_KB_ASSET_PREFIX = "/api/v1/kb/assets/";

    private final ImageStorageService imageStorageService;
    private final ObjectMapper objectMapper;

    public Result normalize(String contentJson, String contentHtml) {
        Map<String, String> hosted = new HashMap<>();
        Set<String> uniqueSources = new HashSet<>();
        String json = normalizeJson(contentJson, hosted, uniqueSources);
        String html = normalizeHtml(contentHtml, hosted, uniqueSources);
        return new Result(json, html);
    }

    private String normalizeJson(String contentJson, Map<String, String> hosted, Set<String> uniqueSources) {
        if (!StringUtils.hasText(contentJson)) return contentJson;
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            normalizeJsonNode(root, hosted, uniqueSources);
            return objectMapper.writeValueAsString(root);
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(400, "contentJson is not valid JSON");
        }
    }

    private void normalizeJsonNode(JsonNode node, Map<String, String> hosted, Set<String> uniqueSources) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            if ("image".equals(object.path("type").asText()) && object.path("attrs").isObject()) {
                ObjectNode attrs = (ObjectNode) object.path("attrs");
                JsonNode source = attrs.get("src");
                if (source != null && source.isTextual()) {
                    attrs.put("src", host(source.asText(), hosted, uniqueSources));
                }
            }
            object.elements().forEachRemaining(child -> normalizeJsonNode(child, hosted, uniqueSources));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> normalizeJsonNode(child, hosted, uniqueSources));
        }
    }

    private String normalizeHtml(String contentHtml, Map<String, String> hosted, Set<String> uniqueSources) {
        if (!StringUtils.hasText(contentHtml)) return contentHtml;
        Document document = Jsoup.parseBodyFragment(contentHtml);
        for (Element image : document.select("img[src]")) {
            image.attr("src", host(image.attr("src"), hosted, uniqueSources));
        }
        return document.body().html();
    }

    private String host(String source, Map<String, String> hosted, Set<String> uniqueSources) {
        String value = source == null ? "" : source.trim();
        if (value.isEmpty() || value.startsWith(LEGACY_KB_ASSET_PREFIX) || !isPastedImageReference(value)) {
            return source;
        }
        String existing = hosted.get(value);
        if (existing != null) return existing;
        if (!uniqueSources.add(value) || uniqueSources.size() > MAX_DISTINCT_IMAGES) {
            throw new BusinessException(400, "A knowledge-base document may contain at most " + MAX_DISTINCT_IMAGES + " pasted images");
        }
        ImageAsset asset = imageStorageService.storeReference(value);
        if (asset == null || !StringUtils.hasText(asset.getImageUrl())) {
            throw new BusinessException(502, "Image upload returned no public URL");
        }
        String url = asset.getImageUrl().trim();
        hosted.put(value, url);
        return url;
    }

    private boolean isPastedImageReference(String value) {
        return value.startsWith("https://") || value.startsWith("http://") || value.startsWith("data:image/");
    }

    @Getter
    @AllArgsConstructor
    public static class Result {
        private final String contentJson;
        private final String contentHtml;
    }
}
