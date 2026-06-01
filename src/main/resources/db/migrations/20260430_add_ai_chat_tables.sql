USE website;

CREATE TABLE IF NOT EXISTS ai_conversation (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    user_id               BIGINT       NOT NULL,
    title                 VARCHAR(120) NOT NULL,
    model                 VARCHAR(100) NOT NULL,
    last_message_preview  VARCHAR(500) DEFAULT NULL,
    last_message_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_conv_user_last (user_id, last_message_at, id),
    CONSTRAINT fk_ai_conv_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chat conversations per user';

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    conversation_id    BIGINT       NOT NULL,
    role               VARCHAR(20)  NOT NULL,
    content            LONGTEXT     NOT NULL,
    model              VARCHAR(100) DEFAULT NULL,
    finish_reason      VARCHAR(50)  DEFAULT NULL,
    prompt_tokens      INT          DEFAULT NULL,
    completion_tokens  INT          DEFAULT NULL,
    total_tokens       INT          DEFAULT NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_msg_conv_id (conversation_id, id),
    CONSTRAINT fk_ai_msg_conversation FOREIGN KEY (conversation_id)
        REFERENCES ai_conversation (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI chat messages';
