package com.example.website.module.identity.application;

import com.example.website.module.identity.infrastructure.persistence.EmailVerificationCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailVerificationAttemptService {

    private final EmailVerificationCodeRepository codeRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long codeId, int maxAttempts) {
        codeRepository.incrementAttempts(codeId, maxAttempts);
    }
}
