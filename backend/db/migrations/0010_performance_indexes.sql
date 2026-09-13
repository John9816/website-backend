-- 性能优化索引创建脚本
-- 版本: 2.0 (精简版，基于实际表结构)
-- 日期: 2026-09-12

USE website;

-- ============================================
-- 1. 用户表 (users)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_last_login_at ON users(last_login_at);
CREATE INDEX IF NOT EXISTS idx_role_created ON users(role, created_at DESC);

-- ============================================
-- 2. 导航链接表 (nav_link)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_category_sort ON nav_link(category_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_user_category ON nav_link(user_id, category_id);
CREATE INDEX IF NOT EXISTS idx_url ON nav_link(url(255));
CREATE INDEX IF NOT EXISTS idx_name ON nav_link(name);

-- ============================================
-- 3. 导航分类表 (category)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_sort ON category(user_id, sort_order);

-- ============================================
-- 4. 音乐播放历史表 (music_play_history)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_played ON music_play_history(user_id, played_at DESC);
CREATE INDEX IF NOT EXISTS idx_source_song_played ON music_play_history(source, song_id, played_at DESC);
CREATE INDEX IF NOT EXISTS idx_played_at ON music_play_history(played_at DESC);

-- ============================================
-- 5. 音乐收藏表 (music_favorite)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_song ON music_favorite(user_id, song_id, source);
CREATE INDEX IF NOT EXISTS idx_user_created ON music_favorite(user_id, created_at DESC);

-- ============================================
-- 6. 用户播放列表表 (user_playlist)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_created ON user_playlist(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_source ON user_playlist(source);

-- ============================================
-- 7. 用户播放列表项表 (user_playlist_item)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_playlist_sort ON user_playlist_item(playlist_id, sort_order);
CREATE INDEX IF NOT EXISTS idx_playlist_song ON user_playlist_item(playlist_id, song_id, source);

-- ============================================
-- 8. 用户书源表 (user_book_source)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_type ON user_book_source(user_id, source_type);
CREATE INDEX IF NOT EXISTS idx_source_name ON user_book_source(source_name);
CREATE INDEX IF NOT EXISTS idx_status ON user_book_source(status, enabled);

-- ============================================
-- 9. 图片生成任务表 (image_generation_task)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_status_created ON image_generation_task(user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_status_created ON image_generation_task(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_completed ON image_generation_task(completed_at DESC);

-- ============================================
-- 10. 生成的图片表 (generated_image)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_created ON generated_image(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_prompt ON generated_image(prompt(100));
CREATE INDEX IF NOT EXISTS idx_shared ON generated_image(is_shared, created_at DESC);

-- ============================================
-- 11. 知识库空间表 (kb_space)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_created ON kb_space(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_name ON kb_space(name);

-- ============================================
-- 12. 知识库文档表 (kb_doc)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_space_status_updated ON kb_doc(space_id, status, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_status_created ON kb_doc(user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_parent_sort ON kb_doc(parent_id, sort_order);

-- ============================================
-- 13. 内容文章表 (content_article)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_status_created ON content_article(user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_status_created ON content_article(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_title ON content_article(title(100));
CREATE INDEX IF NOT EXISTS idx_category ON content_article(category);
CREATE INDEX IF NOT EXISTS idx_quality ON content_article(quality_score DESC);

-- ============================================
-- 14. AI对话表 (ai_conversation)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_last_message ON ai_conversation(user_id, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_title ON ai_conversation(title);

-- ============================================
-- 15. AI消息表 (ai_chat_message)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_conversation_created ON ai_chat_message(conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_role ON ai_chat_message(role, created_at);

-- ============================================
-- 16. 用户签到表 (user_check_in)
-- ============================================
CREATE INDEX IF NOT EXISTS idx_user_date ON user_check_in(user_id, check_in_date DESC);
CREATE INDEX IF NOT EXISTS idx_date ON user_check_in(check_in_date DESC);

-- ============================================
-- 统计信息
-- ============================================
SELECT '✅ 索引创建完成！' AS status;

SELECT
    TABLE_NAME,
    COUNT(*) AS index_count
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'website'
  AND INDEX_NAME LIKE 'idx_%'
GROUP BY TABLE_NAME
ORDER BY index_count DESC;
