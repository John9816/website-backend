-- Run before deploying the September 2026 content automation backend.
CREATE TABLE IF NOT EXISTS content_autopilot_slot (
    slot_key VARCHAR(80) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    slot_at DATETIME NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    claim_token VARCHAR(40),
    claimed_at DATETIME,
    completed_at DATETIME,
    article_id BIGINT,
    next_retry_at DATETIME,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_autopilot_user_slot (user_id, slot_at),
    INDEX idx_autopilot_status (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @has_run_started_at := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'content_automation_task'
      AND column_name = 'run_started_at'
);
SET @add_run_started_at_sql := IF(
    @has_run_started_at = 0,
    'ALTER TABLE content_automation_task ADD COLUMN run_started_at DATETIME NULL',
    'SELECT 1'
);
PREPARE add_run_started_at_stmt FROM @add_run_started_at_sql;
EXECUTE add_run_started_at_stmt;
DEALLOCATE PREPARE add_run_started_at_stmt;
