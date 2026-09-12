CREATE TABLE IF NOT EXISTS email_verification_code (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    email      VARCHAR(100) NOT NULL,
    code_hash  CHAR(64)     NOT NULL,
    expires_at DATETIME     NOT NULL,
    created_at DATETIME     NOT NULL,
    used_at    DATETIME     DEFAULT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    request_ip VARCHAR(45)  DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_email_verification_email_created (email, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Registration email verification codes';
