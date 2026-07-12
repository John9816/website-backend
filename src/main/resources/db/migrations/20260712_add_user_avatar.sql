ALTER TABLE users
    ADD COLUMN avatar_url VARCHAR(255) NULL AFTER auth_version;
