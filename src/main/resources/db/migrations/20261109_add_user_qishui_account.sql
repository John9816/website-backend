CREATE TABLE IF NOT EXISTS user_qishui_account (
    user_id                BIGINT      NOT NULL,
    encrypted_credential   LONGTEXT    NOT NULL,
    binding_id             VARCHAR(36) NOT NULL,
    version                BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_qishui_account_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Encrypted per-user Qishui account credentials';
