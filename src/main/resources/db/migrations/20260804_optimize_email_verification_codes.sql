ALTER TABLE email_verification_code
    ADD INDEX idx_email_verification_ip_created (request_ip, created_at),
    ADD INDEX idx_email_verification_expires (expires_at),
    ADD INDEX idx_email_verification_used (used_at);
