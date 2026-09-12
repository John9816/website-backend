-- Membership periods are granted by one-time redeem codes.
CREATE TABLE IF NOT EXISTS user_membership (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    membership_type   VARCHAR(20)  NOT NULL,
    expires_at        DATETIME     DEFAULT NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_membership_user (user_id),
    CONSTRAINT fk_user_membership_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Current membership entitlement per user';

CREATE TABLE IF NOT EXISTS redeem_code (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    code_hash           CHAR(64)     NOT NULL,
    code_value          VARCHAR(32)  DEFAULT NULL,
    code_preview        VARCHAR(32)  NOT NULL,
    membership_type     VARCHAR(20)  NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'UNUSED',
    created_by_user_id  BIGINT       DEFAULT NULL,
    redeemed_by_user_id BIGINT       DEFAULT NULL,
    redeemed_at         DATETIME     DEFAULT NULL,
    revoked_at          DATETIME     DEFAULT NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_redeem_code_hash (code_hash),
    KEY idx_redeem_code_status_created (status, created_at),
    KEY idx_redeem_code_type_status (membership_type, status),
    CONSTRAINT fk_redeem_code_creator FOREIGN KEY (created_by_user_id)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_redeem_code_redeemer FOREIGN KEY (redeemed_by_user_id)
        REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='One-time membership redeem codes';

CREATE TABLE IF NOT EXISTS membership_redemption (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    redeem_code_id           BIGINT       NOT NULL,
    user_id                  BIGINT       NOT NULL,
    membership_type          VARCHAR(20)  NOT NULL,
    previous_membership_type VARCHAR(20)  DEFAULT NULL,
    previous_expires_at      DATETIME     DEFAULT NULL,
    new_expires_at           DATETIME     DEFAULT NULL,
    redeemed_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_membership_redemption_code (redeem_code_id),
    KEY idx_membership_redemption_user_time (user_id, redeemed_at),
    CONSTRAINT fk_membership_redemption_code FOREIGN KEY (redeem_code_id)
        REFERENCES redeem_code (id),
    CONSTRAINT fk_membership_redemption_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Membership redeem audit log';
