package com.example.website.service.content;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure, stateless text/string helpers shared across the content factory services.
 *
 * <p>These were previously private methods on {@code ContentArticleService}. They are
 * extracted verbatim so call sites can keep using them unchanged via {@code import static}.
 */
public final class ContentTextUtils {

    private ContentTextUtils() {
    }

    public static boolean hasText(String value) {
        return trimToNull(value) != null;
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    public static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public static String escape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static String firstString(Map<?, ?> record, String... keys) {
        for (String key : keys) {
            Object value = record.get(key);
            if (value == null) {
                continue;
            }
            String text = trimToNull(String.valueOf(value));
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    public static String firstText(Map<String, Object> record, String... keys) {
        for (String key : keys) {
            String value = trimToNull(asString(record.get(key)));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static String hashText(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(defaultText(value, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return UUID.nameUUIDFromBytes(defaultText(value, "").getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        }
    }

    public static int countMatches(String value, String needle) {
        if (!hasText(value) || !hasText(needle)) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    public static String htmlToPlainText(String html) {
        return defaultText(html, "").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    public static List<String> readStringList(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<?> raw = (List<?>) value;
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            String text = trimToNull(item == null ? null : String.valueOf(item));
            if (text != null) {
                result.add(text);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return Collections.emptyList();
    }

    public static String extractJsonObject(String value) {
        String text = value.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
