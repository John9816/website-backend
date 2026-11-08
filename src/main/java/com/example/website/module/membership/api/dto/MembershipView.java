package com.example.website.module.membership.api.dto;

import com.example.website.module.membership.domain.MembershipType;
import com.example.website.module.membership.domain.UserMembership;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@Data
public class MembershipView {

    private String type;
    private String typeName;
    private boolean active;
    private boolean lifetime;
    private LocalDateTime expiresAt;
    private Long remainingSeconds;

    public static MembershipView empty() {
        MembershipView view = new MembershipView();
        view.type = null;
        view.typeName = null;
        view.active = false;
        view.lifetime = false;
        view.expiresAt = null;
        view.remainingSeconds = 0L;
        return view;
    }

    public static MembershipView from(UserMembership membership, LocalDateTime now) {
        if (membership == null) {
            return empty();
        }
        MembershipType type = membership.getMembershipType();
        boolean lifetime = type != null && type.isLifetime();
        boolean active = lifetime || (membership.getExpiresAt() != null && membership.getExpiresAt().isAfter(now));
        MembershipView view = new MembershipView();
        view.type = type == null ? null : type.name();
        view.typeName = type == null ? null : type.getDisplayName();
        view.active = active;
        view.lifetime = lifetime;
        view.expiresAt = membership.getExpiresAt();
        if (lifetime) {
            view.remainingSeconds = null;
        } else if (active) {
            view.remainingSeconds = Math.max(0L,
                    membership.getExpiresAt().toEpochSecond(ZoneOffset.UTC) - now.toEpochSecond(ZoneOffset.UTC));
        } else {
            view.remainingSeconds = 0L;
        }
        return view;
    }
}
