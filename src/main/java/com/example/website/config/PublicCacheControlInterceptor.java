package com.example.website.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Component
public class PublicCacheControlInterceptor implements HandlerInterceptor {

    private static final String CACHE_CONTROL_VALUE = "public, max-age=300";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    @Override
    public void postHandle(HttpServletRequest request,
                           HttpServletResponse response,
                           Object handler,
                           @Nullable ModelAndView modelAndView) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return;
        }
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            return;
        }

        String path = request.getRequestURI();
        if (isAlwaysCacheable(path) || isAnonymousPublicListing(path, request)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE);
        }
    }

    private boolean isAlwaysCacheable(String path) {
        return PATH_MATCHER.match("/api/public/kb/share/**", path)
                || PATH_MATCHER.match("/api/public/music/share/**", path);
    }

    private boolean isAnonymousPublicListing(String path, HttpServletRequest request) {
        if (StringUtils.hasText(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            return false;
        }
        return "/api/public/categories".equals(path)
                || "/api/public/nav".equals(path)
                || "/api/public/links".equals(path);
    }
}
