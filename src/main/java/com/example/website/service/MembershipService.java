package com.example.website.service;

import com.example.website.common.BusinessException;
import com.example.website.config.AppProperties;
import com.example.website.dto.PageView;
import com.example.website.dto.membership.MembershipView;
import com.example.website.dto.membership.RedeemCodeAdminView;
import com.example.website.dto.membership.RedeemCodeBatchView;
import com.example.website.dto.membership.RedeemResultView;
import com.example.website.entity.MembershipRedemption;
import com.example.website.entity.MembershipType;
import com.example.website.entity.RedeemCode;
import com.example.website.entity.RedeemCodeStatus;
import com.example.website.entity.User;
import com.example.website.entity.UserMembership;
import com.example.website.repository.MembershipRedemptionRepository;
import com.example.website.repository.RedeemCodeRepository;
import com.example.website.repository.UserMembershipRepository;
import com.example.website.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class MembershipService {

    private static final String CODE_PREFIX = "OW";
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final UserRepository userRepository;
    private final UserMembershipRepository membershipRepository;
    private final RedeemCodeRepository redeemCodeRepository;
    private final MembershipRedemptionRepository redemptionRepository;
    private final AppProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional(readOnly = true)
    public MembershipView getMembership(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        if (props.getMembership().isFreeLifetimeEnabled()) {
            UserMembership freeMembership = new UserMembership();
            freeMembership.setUserId(userId);
            freeMembership.setMembershipType(MembershipType.LIFETIME);
            return MembershipView.from(freeMembership, LocalDateTime.now());
        }
        return MembershipView.from(membershipRepository.findByUserId(userId).orElse(null), LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public void requireActiveMembership(Long userId) {
        requireActiveMembership(userId, "需要有效会员才能播放，请先兑换会员卡");
    }

    @Transactional(readOnly = true)
    public void requireActiveMembership(Long userId, String message) {
        if (userId == null) {
            throw new BusinessException(401, "Unauthorized");
        }
        MembershipView membership = getMembership(userId);
        if (membership.isActive()) {
            return;
        }
        throw new BusinessException(403, message);
    }

    @Transactional(readOnly = true)
    public boolean isAdministrator(Long userId) {
        return userId != null && userRepository.findById(userId)
                .map(user -> User.ROLE_ADMIN.equalsIgnoreCase(user.getRole()))
                .orElse(false);
    }

    @Transactional
    public RedeemResultView redeem(Long userId, String rawCode) {
        if (props.getMembership().isFreeLifetimeEnabled()) {
            throw new BusinessException(409, "当前账号已享有永久会员，无需兑换");
        }
        String code = normalizeCode(rawCode);
        if (!StringUtils.hasText(code)) {
            throw new BusinessException(400, "兑换码不能为空");
        }

        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(404, "User not found"));
        RedeemCode redeemCode = redeemCodeRepository.findByCodeHashForUpdate(hash(code))
                .orElseThrow(() -> new BusinessException(404, "兑换码不存在或无效"));
        if (redeemCode.getStatus() == RedeemCodeStatus.REDEEMED) {
            throw new BusinessException(409, "兑换码已被使用");
        }
        if (redeemCode.getStatus() == RedeemCodeStatus.REVOKED) {
            throw new BusinessException(409, "兑换码已被撤销");
        }

        LocalDateTime now = LocalDateTime.now();
        UserMembership membership = membershipRepository.findByUserIdForUpdate(userId).orElse(null);
        MembershipType previousType = membership == null ? null : membership.getMembershipType();
        LocalDateTime previousExpiresAt = membership == null ? null : membership.getExpiresAt();
        if (previousType != null && previousType.isLifetime() && !redeemCode.getMembershipType().isLifetime()) {
            throw new BusinessException(409, "当前账号已是永久会员，无需兑换此卡");
        }

        if (membership == null) {
            membership = new UserMembership();
            membership.setUserId(userId);
        }
        membership.setMembershipType(redeemCode.getMembershipType());
        if (redeemCode.getMembershipType().isLifetime()) {
            membership.setExpiresAt(null);
        } else {
            LocalDateTime base = previousExpiresAt != null && previousExpiresAt.isAfter(now)
                    ? previousExpiresAt
                    : now;
            membership.setExpiresAt(redeemCode.getMembershipType().addTo(base));
        }
        UserMembership savedMembership = membershipRepository.save(membership);

        redeemCode.setStatus(RedeemCodeStatus.REDEEMED);
        redeemCode.setRedeemedByUserId(userId);
        redeemCode.setRedeemedAt(now);
        redeemCodeRepository.save(redeemCode);

        MembershipRedemption redemption = new MembershipRedemption();
        redemption.setRedeemCodeId(redeemCode.getId());
        redemption.setUserId(userId);
        redemption.setMembershipType(redeemCode.getMembershipType());
        redemption.setPreviousMembershipType(previousType);
        redemption.setPreviousExpiresAt(previousExpiresAt);
        redemption.setNewExpiresAt(savedMembership.getExpiresAt());
        redemptionRepository.save(redemption);

        MembershipView view = MembershipView.from(savedMembership, now);
        return new RedeemResultView("兑换成功", view);
    }

    @Transactional
    public RedeemCodeBatchView generateCodes(Long adminUserId, MembershipType type, int quantity) {
        List<String> codes = new ArrayList<>(quantity);
        for (int i = 0; i < quantity; i++) {
            String code;
            String codeHash;
            do {
                code = generateCode();
                codeHash = hash(code);
            } while (redeemCodeRepository.existsByCodeHash(codeHash));

            RedeemCode entity = new RedeemCode();
            entity.setCodeHash(codeHash);
            entity.setCode(code);
            entity.setCodePreview(maskCode(code));
            entity.setMembershipType(type);
            entity.setStatus(RedeemCodeStatus.UNUSED);
            entity.setCreatedByUserId(adminUserId);
            redeemCodeRepository.save(entity);
            codes.add(code);
        }
        return new RedeemCodeBatchView(type.name(), type.getDisplayName(), codes);
    }

    @Transactional(readOnly = true)
    public PageView<RedeemCodeAdminView> listCodes(String type, String status, int page, int size) {
        Specification<RedeemCode> spec = Specification.where(null);
        if (StringUtils.hasText(type)) {
            MembershipType membershipType = MembershipType.parse(type);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("membershipType"), membershipType));
        }
        if (StringUtils.hasText(status)) {
            RedeemCodeStatus codeStatus = parseStatus(status);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), codeStatus));
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Page<RedeemCode> result = redeemCodeRepository.findAll(
                spec,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt", "id"))
        );
        Set<Long> redeemedUserIds = new HashSet<>();
        for (RedeemCode code : result.getContent()) {
            if (code.getRedeemedByUserId() != null) {
                redeemedUserIds.add(code.getRedeemedByUserId());
            }
        }
        Map<Long, User> redeemedUsers = new HashMap<>();
        userRepository.findAllById(redeemedUserIds)
                .forEach(user -> redeemedUsers.put(user.getId(), user));

        List<RedeemCodeAdminView> items = new ArrayList<>(result.getNumberOfElements());
        for (RedeemCode code : result.getContent()) {
            items.add(RedeemCodeAdminView.from(code, redeemedUsers.get(code.getRedeemedByUserId())));
        }
        return new PageView<>(items, result.getTotalElements(), result.getNumber(), result.getSize());
    }

    @Transactional
    public RedeemCodeAdminView revokeCode(Long codeId) {
        RedeemCode code = redeemCodeRepository.findByIdForUpdate(codeId)
                .orElseThrow(() -> new BusinessException(404, "兑换码不存在"));
        if (code.getStatus() == RedeemCodeStatus.REDEEMED) {
            throw new BusinessException(409, "已使用的兑换码不能撤销");
        }
        if (code.getStatus() == RedeemCodeStatus.REVOKED) {
            return RedeemCodeAdminView.from(code);
        }
        code.setStatus(RedeemCodeStatus.REVOKED);
        code.setRevokedAt(LocalDateTime.now());
        return RedeemCodeAdminView.from(redeemCodeRepository.save(code));
    }

    @Transactional
    public void deleteRevokedCode(Long codeId) {
        RedeemCode code = redeemCodeRepository.findByIdForUpdate(codeId)
                .orElseThrow(() -> new BusinessException(404, "兑换码不存在"));
        if (code.getStatus() != RedeemCodeStatus.REVOKED) {
            throw new BusinessException(409, "只能删除已撤销的兑换码");
        }
        redeemCodeRepository.delete(code);
    }

    @Transactional
    public void batchExtendMembership(Long adminUserId, List<Long> userIds, MembershipType type) {
        LocalDateTime now = LocalDateTime.now();
        for (Long userId : userIds) {
            Optional<UserMembership> membershipOpt = membershipRepository.findByUserIdForUpdate(userId);
            if (membershipOpt.isEmpty()) {
                UserMembership m = new UserMembership();
                m.setUserId(userId);
                m.setMembershipType(type);
                LocalDateTime base = now;
                if (type.isLifetime()) {
                    m.setExpiresAt(null);
                } else {
                    m.setExpiresAt(type.addTo(base));
                }
                membershipRepository.save(m);
            } else {
                UserMembership m = membershipOpt.get();
                LocalDateTime currentExpires = m.getExpiresAt();
                if (type.isLifetime()) {
                    m.setExpiresAt(null);
                } else if (currentExpires != null && currentExpires.isAfter(now)) {
                    m.setExpiresAt(type.addTo(currentExpires));
                } else {
                    m.setExpiresAt(type.addTo(now));
                }
                membershipRepository.save(m);
            }
        }
    }

    private String generateCode() {
        StringBuilder body = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            body.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        }
        return CODE_PREFIX + "-" + body.substring(0, 4) + "-" + body.substring(4, 8)
                + "-" + body.substring(8, 12) + "-" + body.substring(12);
    }

    private String maskCode(String code) {
        return code.substring(0, 7) + "-****-****-" + code.substring(code.length() - 4);
    }

    private String normalizeCode(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private RedeemCodeStatus parseStatus(String value) {
        try {
            return RedeemCodeStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "兑换码状态必须是 UNUSED、REDEEMED 或 REVOKED");
        }
    }
}
