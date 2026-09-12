package com.example.website.config;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;

/** Records bounded server timing dimensions without exposing request contents. */
@Component
public class RequestMetricsInterceptor implements HandlerInterceptor {

    private static final String START_ATTRIBUTE = RequestMetricsInterceptor.class.getName() + ".start";
    private static final Pattern NUMERIC = Pattern.compile("\\d+");
    private final PerformanceMetrics metrics;

    public RequestMetricsInterceptor(PerformanceMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTRIBUTE, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, @Nullable Exception exception) {
        Object start = request.getAttribute(START_ATTRIBUTE);
        if (!(start instanceof Long)) return;
        String route = normalizePath(request.getRequestURI());
        String status = String.valueOf(response.getStatus());
        metrics.recordRequest(request.getMethod(), route, response.getStatus(),
                System.nanoTime() - (Long) start);
    }

    String normalizePath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) return "/";
        StringBuilder result = new StringBuilder(64);
        for (String segment : rawPath.split("/")) {
            if (segment.isEmpty()) continue;
            result.append('/');
            if (NUMERIC.matcher(segment).matches()) {
                result.append("{id}");
            } else if (segment.length() > 12) {
                result.append("{token}");
            } else {
                result.append(segment);
            }
            if (result.length() >= 120) break;
        }
        return result.length() == 0 ? "/" : result.toString();
    }
}
