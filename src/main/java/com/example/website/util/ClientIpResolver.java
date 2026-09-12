package com.example.website.util;

import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for requests that reach the app behind a
 * Cloudflare Worker / reverse proxy. Order of preference:
 * <ol>
 *     <li>{@code X-Real-IP} — overwritten by the trusted ECS reverse proxy</li>
 *     <li>{@code CF-Connecting-IP} — set by Cloudflare when that path is used</li>
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
        String remote = request.getRemoteAddr();
        if (isLoopback(remote)) {
            String realIp = request.getHeader("X-Real-IP");
            if (StringUtils.hasText(realIp)) {
                return truncate(realIp.trim());
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
        }
        return StringUtils.hasText(remote) ? truncate(remote.trim()) : null;
    }

    private static boolean isLoopback(String value) {
        return StringUtils.hasText(value)
                && (value.startsWith("127.") || "::1".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value));
    }

    private static String truncate(String value) {
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }
}
