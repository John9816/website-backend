package com.example.website.util;

import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for requests that reach the app behind a
 * Cloudflare Worker / reverse proxy. Order of preference:
 * <ol>
 *     <li>{@code CF-Connecting-IP} — set by Cloudflare, the real client IP</li>
 *     <li>{@code X-Forwarded-For} — first hop is the original client</li>
 *     <li>{@link HttpServletRequest#getRemoteAddr()} — direct connection fallback</li>
 * </ol>
 * The result is trimmed to fit a VARCHAR(45) column (max length of an IPv6 address).
 */
public final class ClientIpResolver {

    private static final int MAX_LENGTH = 45;

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (StringUtils.hasText(cfIp)) {
            return truncate(cfIp.trim());
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            String first = comma >= 0 ? forwarded.substring(0, comma) : forwarded;
            if (StringUtils.hasText(first)) {
                return truncate(first.trim());
            }
        }
        String remote = request.getRemoteAddr();
        return StringUtils.hasText(remote) ? truncate(remote.trim()) : null;
    }

    private static String truncate(String value) {
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }
}
