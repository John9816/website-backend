CREATE TABLE IF NOT EXISTS image_asset (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    image_url     VARCHAR(1000) NOT NULL,
    url_hash      VARCHAR(64)   NOT NULL,
    host          VARCHAR(100)  DEFAULT NULL,
    delete_url    VARCHAR(2048) DEFAULT NULL,
    source        VARCHAR(255)  DEFAULT NULL,
    response_json LONGTEXT      NOT NULL,
    content_type  VARCHAR(100)  DEFAULT NULL,
    byte_size     BIGINT        DEFAULT NULL,
    created_at    DATETIME      NOT NULL,
    INDEX idx_image_asset_url_hash (url_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE users MODIFY COLUMN avatar_url VARCHAR(1000) NULL;
