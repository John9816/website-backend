package com.example.website.dto.membership;

import com.example.website.entity.RedeemCode;
import com.example.website.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedeemCodeAdminView {

    private Long id;
    private String code;
    private String codePreview;
    private String type;
    private String typeName;
    private String status;
    private Long redeemedByUserId;
    private String redeemedByUsername;
    private String redeemedByEmail;
    private LocalDateTime redeemedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;

    public static RedeemCodeAdminView from(RedeemCode code) {
        return from(code, null);
    }

    public static RedeemCodeAdminView from(RedeemCode code, User redeemedBy) {
        RedeemCodeAdminView view = new RedeemCodeAdminView();
        view.id = code.getId();
        view.code = code.getCode() == null ? code.getCodePreview() : code.getCode();
        view.codePreview = code.getCodePreview();
        view.type = code.getMembershipType().name();
        view.typeName = code.getMembershipType().getDisplayName();
        view.status = code.getStatus().name();
        view.redeemedByUserId = code.getRedeemedByUserId();
        view.redeemedByUsername = redeemedBy == null ? null : redeemedBy.getUsername();
        view.redeemedByEmail = redeemedBy == null ? null : redeemedBy.getEmail();
        view.redeemedAt = code.getRedeemedAt();
        view.revokedAt = code.getRevokedAt();
        view.createdAt = code.getCreatedAt();
        return view;
    }
}
