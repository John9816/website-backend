USE website;

SET @ddl = (
    SELECT IF(
        EXISTS(
            SELECT 1
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'generated_image'
              AND COLUMN_NAME = 'image_data'
        ),
        'SELECT ''generated_image.image_data already exists''',
        'ALTER TABLE generated_image ADD COLUMN image_data LONGTEXT NULL AFTER image_url'
    )
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
