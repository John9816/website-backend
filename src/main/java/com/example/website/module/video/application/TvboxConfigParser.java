package com.example.website.module.video.application;

import com.example.website.module.video.api.dto.VideoSourceCapability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses TVBox metadata without evaluating api, ext, spider or jar. */
public final class TvboxConfigParser {
    private final ObjectMapper mapper;
    private final int maxBytes;

    public TvboxConfigParser(ObjectMapper mapper, int maxBytes) {
        if (mapper == null || maxBytes < 1024) throw new IllegalArgumentException("TVBox parser 配置非法");
        this.mapper = mapper;
        this.maxBytes = maxBytes;
    }

    public TvboxConfig parse(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("TVBox 配置超过大小限制");
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null || !root.isObject()) throw new IllegalArgumentException("TVBox 配置必须是对象");
            JsonNode sitesNode = root.get("sites");
            if (sitesNode == null || !sitesNode.isArray()) throw new IllegalArgumentException("TVBox 配置缺少 sites");
            List<TvboxSite> sites = new ArrayList<>();
            Set<String> keys = new HashSet<>();
            for (JsonNode node : sitesNode) {
                TvboxSite site = parseSite(node);
                if (!keys.add(site.getKey())) throw new IllegalArgumentException("TVBox site key 重复: " + site.getKey());
                sites.add(site);
            }
            return new TvboxConfig(sites);
        } catch (IOException ex) {
            throw new IllegalArgumentException("TVBox 配置 JSON 无效", ex);
        }
    }

    private TvboxSite parseSite(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("TVBox site 必须是对象");
        String key = text(node, "key");
        String name = text(node, "name");
        if (key.isEmpty() || name.isEmpty()) throw new IllegalArgumentException("TVBox site 缺少 key/name");
        String api = text(node, "api");
        int type = node.path("type").asInt(0);
        Object ext = node.has("ext") ? mapper.convertValue(node.get("ext"), Object.class) : null;
        String jar = text(node, "jar");
        String spider = text(node, "spider");
        Map<String, String> headers = new LinkedHashMap<>();
        JsonNode headersNode = node.get("headers");
        if (headersNode != null && headersNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                headers.put(field.getKey(), field.getValue().asText(""));
            }
        }
        ProviderCapabilityDetector.Detection detection = ProviderCapabilityDetector.detect(
                type, api, ext, jar, spider);
        return new TvboxSite(key, name, type, api, ext, jar, spider, headers,
                node.path("searchable").asInt(0) != 0,
                node.path("quickSearch").asInt(0) != 0,
                node.path("filterable").asInt(0) != 0,
                detection.capability, detection.executable, detection.reason);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }
}
