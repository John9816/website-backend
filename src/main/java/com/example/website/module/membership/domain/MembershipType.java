package com.example.website.module.membership.domain;

import com.example.website.common.BusinessException;

import java.time.LocalDateTime;
import java.util.Locale;

public enum MembershipType {
    DAY("日卡"),
    WEEK("周卡"),
    MONTH("月卡"),
    YEAR("年卡"),
    LIFETIME("永久");

    private final String displayName;

    MembershipType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isLifetime() {
        return this == LIFETIME;
    }

    public LocalDateTime addTo(LocalDateTime base) {
        if (isLifetime()) {
            return null;
        }
        switch (this) {
            case DAY:
                return base.plusDays(1);
            case WEEK:
                return base.plusWeeks(1);
            case MONTH:
                return base.plusMonths(1);
            case YEAR:
                return base.plusYears(1);
            default:
                throw new IllegalStateException("Unsupported membership type: " + this);
        }
    }

    public static MembershipType parse(String value) {
        if (value == null) {
            throw new BusinessException(400, "会员类型不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("PERMANENT".equals(normalized) || "永久卡".equals(value.trim())) {
            normalized = "LIFETIME";
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "会员类型必须是 DAY、WEEK、MONTH、YEAR 或 LIFETIME");
        }
    }
}
