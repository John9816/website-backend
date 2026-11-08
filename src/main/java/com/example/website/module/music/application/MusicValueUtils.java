package com.example.website.module.music.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MusicValueUtils {

    private MusicValueUtils() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    static Integer asInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    static Long asLongObj(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong((String) value); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return null;
    }

    /** Extracts stable IDs from an upstream list of artist/singer maps. */
    static List<String> idsFromMaps(Object value, String... keys) {
        if (value instanceof Map) value = Collections.singletonList(value);
        if (!(value instanceof List)) return Collections.emptyList();
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : (List<?>) value) {
            Map<String, Object> map = asMap(item);
            if (map == null) continue;
            for (String key : keys) {
                String id = asString(map.get(key));
                if (id != null && !id.trim().isEmpty()) {
                    ids.add(id.trim());
                    break;
                }
            }
        }
        return new ArrayList<>(ids);
    }

    /** Extract IDs from either an upstream artist object/list or a delimited scalar. */
    static List<String> idsFromValue(Object value, String... keys) {
        List<String> mapped = idsFromMaps(value, keys);
        if (!mapped.isEmpty()) return mapped;
        String raw = asString(value);
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        Set<String> ids = new LinkedHashSet<>();
        for (String token : raw.split("[,;|/&\\s]+")) {
            String id = token.trim();
            if (!id.isEmpty()) ids.add(id);
        }
        return new ArrayList<>(ids);
    }

    static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 200 ? value.substring(0, 200) + "..." : value;
    }
}
