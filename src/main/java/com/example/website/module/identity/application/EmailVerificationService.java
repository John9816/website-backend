package com.example.website.module.identity.application;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import com.example.website.module.identity.api.dto.EmailVerificationCodeView;
import com.example.website.module.identity.domain.EmailVerificationCode;
import com.example.website.module.identity.domain.EmailVerificationPurpose;
import com.example.website.module.identity.infrastructure.persistence.EmailVerificationCodeRepository;
import com.example.website.module.identity.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final int CODE_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final CloudMailClient cloudMailClient;
    private final EmailVerificationAttemptService attemptService;
    private final RegistrationEmailRateLimiter rateLimiter;
    private final AppProperties props;

    public EmailVerificationCodeView sendRegistrationCode(String rawEmail, String requestIp) {
        return sendCode(rawEmail, requestIp, EmailVerificationPurpose.REGISTER);
    }

    public EmailVerificationCodeView sendPasswordResetCode(String rawEmail, String requestIp) {
        return sendCode(rawEmail, requestIp, EmailVerificationPurpose.PASSWORD_RESET);
    }

    private EmailVerificationCodeView sendCode(String rawEmail,
                                               String requestIp,
                                               EmailVerificationPurpose purpose) {
        String email = normalizeEmail(rawEmail);
        AppProperties.EmailVerification settings = props.getRegistration().getEmailVerification();
        boolean emailExists = userRepository.existsByEmail(email);
        if (purpose == EmailVerificationPurpose.REGISTER ? emailExists : !emailExists) {
            return deliveryView(settings);
        }

        LocalDateTime now = LocalDateTime.now();
        EmailVerificationCode latest = codeRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .orElse(null);
        if (latest != null && latest.getCreatedAt() != null
                && Duration.between(latest.getCreatedAt(), now).getSeconds() < settings.getResendIntervalSeconds()) {
            throw new BusinessException(429, "验证码发送过于频繁，请稍后再试");
        }
        if (StringUtils.hasText(requestIp)) {
            LocalDateTime ipWindowStart = now.minusMinutes(Math.max(1, settings.getIpWindowMinutes()));
            long recentIpSends = codeRepository.countByRequestIpAndCreatedAtAfter(requestIp, ipWindowStart);
            if (recentIpSends >= Math.max(1, settings.getIpMaxSends())) {
                throw new BusinessException(429, "验证码发送过于频繁，请稍后再试");
            }
        }
        rateLimiter.acquire(email, requestIp, settings);

        String code = generateCode();
        EmailVerificationCode entity = new EmailVerificationCode();
        entity.setEmail(email);
        entity.setPurpose(purpose);
        entity.setCodeHash(hash(email, code));
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plusMinutes(settings.getExpireMinutes()));
        entity.setRequestIp(requestIp);
        entity.setAttempts(0);
        codeRepository.saveAndFlush(entity);

        if (purpose == EmailVerificationPurpose.REGISTER) {
            cloudMailClient.sendRegistrationCode(email, code, settings.getExpireMinutes());
        } else {
            cloudMailClient.sendPasswordResetCode(email, code, settings.getExpireMinutes());
        }
        return deliveryView(settings);
    }

    private EmailVerificationCodeView deliveryView(AppProperties.EmailVerification settings) {
        return new EmailVerificationCodeView(
                Math.max(1, settings.getResendIntervalSeconds()),
                Math.max(1, settings.getExpireMinutes()) * 60);
    }

    @Transactional
    public void verifyRegistrationCode(String rawEmail, String rawCode) {
        verifyAndConsume(rawEmail, rawCode, EmailVerificationPurpose.REGISTER);
    }

    public void verifyPasswordResetCode(String rawEmail, String rawCode) {
        verifyAndConsume(rawEmail, rawCode, EmailVerificationPurpose.PASSWORD_RESET);
    }

    private void verifyAndConsume(String rawEmail,
                                  String rawCode,
                                  EmailVerificationPurpose purpose) {
        String email = normalizeEmail(rawEmail);
        if (!StringUtils.hasText(rawCode) || !rawCode.trim().matches("\\d{" + CODE_LENGTH + "}")) {
            throw new BusinessException(400, "邮箱验证码格式不正确");
        }

        EmailVerificationCode entity = codeRepository
                .findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(() -> invalidCode());
        AppProperties.EmailVerification settings = props.getRegistration().getEmailVerification();
        LocalDateTime now = LocalDateTime.now();
        if (entity.getAttempts() >= settings.getMaxAttempts()) {
            throw new BusinessException(429, "验证码错误次数过多，请重新获取验证码");
        }
        if (entity.getExpiresAt() == null || !entity.getExpiresAt().isAfter(now)) {
            throw invalidCode();
        }
        if (!matches(email, rawCode.trim(), entity.getCodeHash())) {
            attemptService.recordFailure(entity.getId(), settings.getMaxAttempts());
            throw invalidCode();
        }

        entity.setUsedAt(now);
        codeRepository.save(entity);
    }

    private String generateCode() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    private String normalizeEmail(String rawEmail) {
        if (!StringUtils.hasText(rawEmail)) {
            throw new BusinessException(400, "Email is required");
        }
        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matches(String email, String code, String storedHash) {
        String hmacHash = hash(email, code);
        if (constantTimeEquals(hmacHash, storedHash)) {
            return true;
        }
        return constantTimeEquals(legacySha256(code), storedHash);
    }

    private String hash(String email, String code) {
        String secret = props.getRegistration().getEmailVerification().getHmacSecret();
        if (!StringUtils.hasText(secret)) {
            secret = props.getJwt().getSecret();
        }
        if (!StringUtils.hasText(secret)) {
            throw new BusinessException(503, "邮箱验证码签名密钥尚未配置");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal((email + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        }
    }

    private String legacySha256(String value) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private String toHex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format(Locale.ROOT, "%02x", item));
        }
        return result.toString();
    }

    private BusinessException invalidCode() {
        return new BusinessException(400, "邮箱验证码不存在、已过期或不正确");
    }
}
