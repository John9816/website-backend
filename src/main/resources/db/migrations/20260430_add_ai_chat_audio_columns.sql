USE website;

SET @ddl = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'ai_chat_message'
              AND COLUMN_NAME = 'audio_model'
        ),
        'SELECT ''ai_chat_message audio columns already exist''',
        'ALTER TABLE ai_chat_message
            ADD COLUMN audio_model VARCHAR(100) NULL AFTER model,
            ADD COLUMN audio_source_url VARCHAR(1000) NULL AFTER audio_model,
            ADD COLUMN audio_data LONGTEXT NULL AFTER audio_source_url,
            ADD COLUMN audio_mime_type VARCHAR(100) NULL AFTER audio_data,
            ADD COLUMN audio_external_id VARCHAR(120) NULL AFTER audio_mime_type'
    )
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
