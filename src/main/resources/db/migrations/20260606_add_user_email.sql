ALTER TABLE `user`
    ADD COLUMN email VARCHAR(100) DEFAULT NULL AFTER username;

CREATE UNIQUE INDEX uk_user_email ON `user` (email);
