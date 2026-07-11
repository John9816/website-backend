ALTER TABLE users
    ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1 AFTER role,
    ADD COLUMN auth_version INT NOT NULL DEFAULT 0 AFTER enabled;

CREATE INDEX idx_users_role_enabled_created
    ON users (role, enabled, created_at, id);
