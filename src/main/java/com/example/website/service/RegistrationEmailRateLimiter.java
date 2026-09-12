package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

@Component
public class RegistrationEmailRateLimiter {

    private static final long ONE_MINUTE_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final Cache<String, Long> emailNextAllowedAt = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();
    private final Cache<String, WindowCounter> ipWindows = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();
    private final Deque<Long> globalSendTimes = new ArrayDeque<>();

    public synchronized void acquire(String email,
                                     String requestIp,
                                     AppProperties.EmailVerification settings) {
        long now = System.currentTimeMillis();
        Long nextAllowedAt = emailNextAllowedAt.getIfPresent(email);
        if (nextAllowedAt != null && nextAllowedAt > now) {
            throw tooManyRequests();
        }

        String ipKey = StringUtils.hasText(requestIp) ? requestIp : "unknown";
        long ipWindowMillis = TimeUnit.MINUTES.toMillis(Math.max(1, settings.getIpWindowMinutes()));
        WindowCounter ipCounter = ipWindows.getIfPresent(ipKey);
        if (ipCounter == null || now - ipCounter.windowStartedAt >= ipWindowMillis) {
            ipCounter = new WindowCounter(now, 0);
        }
        if (ipCounter.count >= Math.max(1, settings.getIpMaxSends())) {
            throw tooManyRequests();
        }

        while (!globalSendTimes.isEmpty() && now - globalSendTimes.peekFirst() >= ONE_MINUTE_MILLIS) {
            globalSendTimes.removeFirst();
        }
        if (globalSendTimes.size() >= Math.max(1, settings.getGlobalMaxSendsPerMinute())) {
            throw tooManyRequests();
        }

        emailNextAllowedAt.put(email,
                now + TimeUnit.SECONDS.toMillis(Math.max(1, settings.getResendIntervalSeconds())));
        ipCounter.count++;
        ipWindows.put(ipKey, ipCounter);
        globalSendTimes.addLast(now);
    }

    private BusinessException tooManyRequests() {
        return new BusinessException(429, "验证码发送过于频繁，请稍后再试");
    }

    private static final class WindowCounter {
        private final long windowStartedAt;
        private int count;

        private WindowCounter(long windowStartedAt, int count) {
            this.windowStartedAt = windowStartedAt;
            this.count = count;
        }
    }
}
