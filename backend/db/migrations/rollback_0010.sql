-- 回滚脚本: 删除所有性能优化索引
-- 执行: mysql -u root -p website < rollback_0010.sql

USE website;

-- 用户表索引
DROP INDEX IF EXISTS idx_email ON users;
DROP INDEX IF EXISTS idx_created_at ON users;
DROP INDEX IF EXISTS idx_last_check_in ON users;

-- 导航链接索引
DROP INDEX IF EXISTS idx_category_order ON nav_links;
DROP INDEX IF EXISTS idx_url ON nav_links;
DROP INDEX IF EXISTS idx_is_active ON nav_links;

-- 导航分类索引
DROP INDEX IF EXISTS idx_parent_order ON nav_categories;
DROP INDEX IF EXISTS idx_name ON nav_categories;

-- 音乐歌曲索引
DROP INDEX IF EXISTS idx_provider_id_order ON music_songs;
DROP INDEX IF EXISTS idx_provider_id ON music_songs;
DROP INDEX IF EXISTS idx_name_artist ON music_songs;
DROP INDEX IF EXISTS idx_album ON music_songs;

-- 音乐播放列表索引
DROP INDEX IF EXISTS idx_provider_type ON music_playlists;
DROP INDEX IF EXISTS idx_provider_id ON music_playlists;
DROP INDEX IF EXISTS idx_name ON music_playlists;

-- 音乐榜单索引
DROP INDEX IF EXISTS idx_provider_type ON music_charts;
DROP INDEX IF EXISTS idx_provider_id ON music_charts;

-- 用户播放记录索引
DROP INDEX IF EXISTS idx_user_song ON user_music_play_records;
DROP INDEX IF EXISTS idx_user_created ON user_music_play_records;
DROP INDEX IF EXISTS idx_song_created ON user_music_play_records;

-- 用户音乐提供商索引
DROP INDEX IF EXISTS idx_user_provider ON user_music_providers;

-- 用户书源索引
DROP INDEX IF EXISTS idx_user_source ON user_book_sources;

-- 图片生成任务索引
DROP INDEX IF EXISTS idx_user_status_created ON image_generation_tasks;
DROP INDEX IF EXISTS idx_status_created ON image_generation_tasks;
DROP INDEX IF EXISTS idx_prompt ON image_generation_tasks;

-- 知识库条目索引
DROP INDEX IF EXISTS idx_user_created ON kb_entries;
DROP INDEX IF EXISTS idx_user_category ON kb_entries;
DROP INDEX IF EXISTS idx_title ON kb_entries;
DROP INDEX IF EXISTS idx_tags ON kb_entries;

-- 内容文章索引
DROP INDEX IF EXISTS idx_user_status_published ON content_articles;
DROP INDEX IF EXISTS idx_status_published ON content_articles;
DROP INDEX IF EXISTS idx_title ON content_articles;
DROP INDEX IF EXISTS idx_slug ON content_articles;
DROP INDEX IF EXISTS idx_category ON content_articles;
DROP INDEX IF EXISTS idx_automation_config ON content_articles;

SELECT '✅ 所有性能索引已删除' AS status;
