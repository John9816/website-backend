package com.example.website.module.identity.application;

import com.example.website.config.AppProperties;
import com.example.website.module.identity.infrastructure.persistence.EmailVerificationCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class EmailVerificationCleanupService {

    private final EmailVerificationCodeRepository codeRepository;
    private final AppProperties props;

    @Scheduled(fixedDelayString = "${APP_EMAIL_CODE_CLEANUP_INTERVAL_MS:86400000}",
            initialDelayString = "${APP_EMAIL_CODE_CLEANUP_INITIAL_DELAY_MS:60000}")
    @Transactional
    public void removeOldCodes() {
        long retentionHours = Math.max(1,
                props.getRegistration().getEmailVerification().getCleanupRetentionHours());
        codeRepository.deleteExpiredOrUsedBefore(LocalDateTime.now().minusHours(retentionHours));
    }
}
