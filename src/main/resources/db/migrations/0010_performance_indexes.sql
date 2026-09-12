-- 性能优化：为高频查询添加索引
-- 执行前请先备份数据库

-- 用户表索引
ALTER TABLE `user` ADD INDEX `idx_username` (`username`);
ALTER TABLE `user` ADD INDEX `idx_email` (`email`);
ALTER TABLE `user` ADD INDEX `idx_role_created` (`role`, `created_at`);

-- 导航分类索引
ALTER TABLE `category` ADD INDEX `idx_user_order` (`user_id`, `sort_order`);

-- 导航链接索引
ALTER TABLE `nav_link` ADD INDEX `idx_category_order` (`category_id`, `sort_order`);
ALTER TABLE `nav_link` ADD INDEX `idx_user_category` (`user_id`, `category_id`);

-- 图片生成任务索引
ALTER TABLE `image_generation_task` ADD INDEX `idx_user_status_created` (`user_id`, `status`, `created_at` DESC);
ALTER TABLE `image_generation_task` ADD INDEX `idx_status_created` (`status`, `created_at`);

-- 图片历史索引
ALTER TABLE `generated_image` ADD INDEX `idx_user_created` (`user_id`, `created_at` DESC);
ALTER TABLE `generated_image` ADD INDEX `idx_shared_created` (`is_shared`, `created_at` DESC);

-- AI对话索引
ALTER TABLE `ai_conversation` ADD INDEX `idx_user_updated` (`user_id`, `updated_at` DESC);

-- AI消息索引
ALTER TABLE `ai_chat_message` ADD INDEX `idx_conversation_created` (`conversation_id`, `created_at`);

-- 知识库空间索引
ALTER TABLE `kb_space` ADD INDEX `idx_user_updated` (`user_id`, `updated_at` DESC);

-- 知识库文档索引
ALTER TABLE `kb_doc` ADD INDEX `idx_space_parent` (`space_id`, `parent_id`);
ALTER TABLE `kb_doc` ADD INDEX `idx_user_status` (`user_id`, `status`);
ALTER TABLE `kb_doc` ADD INDEX `idx_updated` (`updated_at` DESC);

-- 知识库文档标签关联索引
ALTER TABLE `kb_doc_tag` ADD INDEX `idx_tag_doc` (`tag_id`, `doc_id`);

-- 知识库分享索引
ALTER TABLE `kb_doc_share` ADD INDEX `idx_token` (`token`);
ALTER TABLE `kb_doc_share` ADD INDEX `idx_doc_expires` (`doc_id`, `expires_at`);

-- 音乐播放历史索引
ALTER TABLE `music_play_history` ADD INDEX `idx_user_played` (`user_id`, `played_at` DESC);
ALTER TABLE `music_play_history` ADD INDEX `idx_user_source_song` (`user_id`, `source`, `song_id`);

-- 音乐收藏索引
ALTER TABLE `music_favorite` ADD INDEX `idx_user_created` (`user_id`, `created_at` DESC);
ALTER TABLE `music_favorite` ADD INDEX `idx_user_source_song` (`user_id`, `source`, `song_id`);

-- 音乐分享索引
ALTER TABLE `music_share` ADD INDEX `idx_token` (`token`);
ALTER TABLE `music_share` ADD INDEX `idx_user_source_song` (`user_id`, `source`, `song_id`);
ALTER TABLE `music_share` ADD INDEX `idx_expires` (`expires_at`);

-- 用户歌单索引
ALTER TABLE `user_playlist` ADD INDEX `idx_user_created` (`user_id`, `created_at` DESC);
ALTER TABLE `user_playlist` ADD INDEX `idx_source_id` (`source`, `source_id`);

-- 用户歌单曲目索引
ALTER TABLE `user_playlist_item` ADD INDEX `idx_playlist_order` (`playlist_id`, `sort_order`);

-- 会员兑换码索引
ALTER TABLE `redeem_code` ADD INDEX `idx_status_type` (`status`, `type`);
ALTER TABLE `redeem_code` ADD INDEX `idx_created` (`created_at` DESC);

-- 刷新令牌索引
ALTER TABLE `refresh_token` ADD INDEX `idx_user_expires` (`user_id`, `expires_at`);
ALTER TABLE `refresh_token` ADD INDEX `idx_expires` (`expires_at`);

-- 邮箱验证码索引
ALTER TABLE `email_verification_code` ADD INDEX `idx_email_purpose` (`email`, `purpose`, `created_at` DESC);
ALTER TABLE `email_verification_code` ADD INDEX `idx_created` (`created_at`);

-- 系统配置索引（如果没有唯一键）
-- ALTER TABLE `sys_config` ADD UNIQUE INDEX `uk_config_key` (`config_key`);

-- 验证索引创建
SELECT
    TABLE_NAME,
    INDEX_NAME,
    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS COLUMNS
FROM
    INFORMATION_SCHEMA.STATISTICS
WHERE
    TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME IN (
        'user', 'category', 'nav_link', 'image_generation_task', 'generated_image',
        'ai_conversation', 'ai_chat_message', 'kb_space', 'kb_doc', 'kb_doc_tag',
        'kb_doc_share', 'music_play_history', 'music_favorite', 'music_share',
        'user_playlist', 'user_playlist_item', 'redeem_code', 'refresh_token',
        'email_verification_code'
    )
    AND INDEX_NAME != 'PRIMARY'
GROUP BY
    TABLE_NAME, INDEX_NAME
ORDER BY
    TABLE_NAME, INDEX_NAME;
