package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistrationEmailRateLimiterTests {

    @Test
    void limitsRepeatedEmailRequests() {
        RegistrationEmailRateLimiter limiter = new RegistrationEmailRateLimiter();
        AppProperties.EmailVerification settings = settings();

        assertDoesNotThrow(() -> limiter.acquire("one@751152.xyz", "198.51.100.1", settings));
        assertThrows(BusinessException.class,
                () -> limiter.acquire("one@751152.xyz", "198.51.100.2", settings));
    }

    @Test
    void limitsRequestsAcrossDifferentEmailsFromSameIp() {
        RegistrationEmailRateLimiter limiter = new RegistrationEmailRateLimiter();
        AppProperties.EmailVerification settings = settings();
        settings.setIpMaxSends(2);

        limiter.acquire("one@751152.xyz", "198.51.100.3", settings);
        limiter.acquire("two@751152.xyz", "198.51.100.3", settings);

        assertThrows(BusinessException.class,
                () -> limiter.acquire("three@751152.xyz", "198.51.100.3", settings));
    }

    @Test
    void appliesGlobalPerMinuteLimit() {
        RegistrationEmailRateLimiter limiter = new RegistrationEmailRateLimiter();
        AppProperties.EmailVerification settings = settings();
        settings.setGlobalMaxSendsPerMinute(2);

        limiter.acquire("one@751152.xyz", "198.51.100.4", settings);
        limiter.acquire("two@751152.xyz", "198.51.100.5", settings);

        assertThrows(BusinessException.class,
                () -> limiter.acquire("three@751152.xyz", "198.51.100.6", settings));
    }

    private AppProperties.EmailVerification settings() {
        AppProperties.EmailVerification settings = new AppProperties.EmailVerification();
        settings.setResendIntervalSeconds(60);
        settings.setIpWindowMinutes(60);
        settings.setIpMaxSends(10);
        settings.setGlobalMaxSendsPerMinute(60);
        return settings;
    }
}
