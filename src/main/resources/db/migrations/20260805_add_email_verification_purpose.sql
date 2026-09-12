SET @ddl = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'email_verification_code'
              AND COLUMN_NAME = 'purpose'
        ),
        'SELECT ''email_verification_code.purpose already exists''',
        'ALTER TABLE email_verification_code
            ADD COLUMN purpose VARCHAR(24) NOT NULL DEFAULT ''REGISTER'' AFTER email'
    )
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'email_verification_code'
              AND INDEX_NAME = 'idx_email_verification_email_purpose_created'
        ),
        'SELECT ''idx_email_verification_email_purpose_created already exists''',
        'CREATE INDEX idx_email_verification_email_purpose_created
            ON email_verification_code (email, purpose, created_at)'
    )
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
