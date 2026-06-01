CREATE TABLE IF NOT EXISTS image_generation_task (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    prompt          VARCHAR(2000) NOT NULL,
    size            VARCHAR(20)   DEFAULT NULL,
    n               INT           NOT NULL DEFAULT 1,
    model           VARCHAR(100)  NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, COMPLETED, FAILED',
    result_json     LONGTEXT      DEFAULT NULL COMMENT 'ImageGenerationsResponse JSON when COMPLETED',
    error_message   VARCHAR(1000) DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at    DATETIME      DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_igt_user_status (user_id, status, created_at),
    CONSTRAINT fk_igt_user FOREIGN KEY (user_id)
        REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Async image generation tasks';
