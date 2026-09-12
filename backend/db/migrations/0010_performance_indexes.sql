-- 性能优化索引创建脚本
-- 版本: 1.0
-- 日期: 2026-09-12
-- 说明: 为核心业务表创建性能索引

USE website;

-- ============================================
-- 1. 用户表 (users)
-- ============================================

-- 邮箱查询索引
CREATE INDEX IF NOT EXISTS idx_email ON users(email);

-- 用户创建时间索引（用于统计和排序）
CREATE INDEX IF NOT EXISTS idx_created_at ON users(created_at);

-- 签到时间索引（用于签到功能查询）
CREATE INDEX IF NOT EXISTS idx_last_check_in ON users(last_check_in_date);

-- ============================================
-- 2. 导航链接表 (nav_links)
-- ============================================

-- 分类+排序组合索引（最常用的查询场景）
CREATE INDEX IF NOT EXISTS idx_category_order ON nav_links(category_id, display_order);

-- URL查询索引（用于去重和查找）
CREATE INDEX IF NOT EXISTS idx_url ON nav_links(url(255));

-- 状态索引（用于筛选活跃链接）
CREATE INDEX IF NOT EXISTS idx_is_active ON nav_links(is_active);

-- ============================================
-- 3. 导航分类表 (nav_categories)
-- ============================================

-- 父分类+排序组合索引
CREATE INDEX IF NOT EXISTS idx_parent_order ON nav_categories(parent_id, display_order);

-- 分类名称索引
CREATE INDEX IF NOT EXISTS idx_name ON nav_categories(name);

-- ============================================
-- 4. 音乐歌曲表 (music_songs)
-- ============================================

-- 提供商+ID+排序组合索引（播放列表查询）
CREATE INDEX IF NOT EXISTS idx_provider_id_order ON music_songs(provider, provider_song_id, display_order);

-- 提供商+ID索引（单曲查询）
CREATE INDEX IF NOT EXISTS idx_provider_id ON music_songs(provider, provider_song_id);

-- 歌曲名+歌手组合索引（搜索场景）
CREATE INDEX IF NOT EXISTS idx_name_artist ON music_songs(name(100), artist(100));

-- 专辑索引
CREATE INDEX IF NOT EXISTS idx_album ON music_songs(album(100));

-- ============================================
-- 5. 音乐播放列表表 (music_playlists)
-- ============================================

-- 提供商+类型组合索引
CREATE INDEX IF NOT EXISTS idx_provider_type ON music_playlists(provider, playlist_type);

-- 提供商+ID索引（单个播放列表查询）
CREATE INDEX IF NOT EXISTS idx_provider_id ON music_playlists(provider, provider_playlist_id);

-- 播放列表名称索引
CREATE INDEX IF NOT EXISTS idx_name ON music_playlists(name(100));

-- ============================================
-- 6. 音乐榜单表 (music_charts)
-- ============================================

-- 提供商+类型组合索引
CREATE INDEX IF NOT EXISTS idx_provider_type ON music_charts(provider, chart_type);

-- 提供商+ID索引
CREATE INDEX IF NOT EXISTS idx_provider_id ON music_charts(provider, provider_chart_id);

-- ============================================
-- 7. 用户播放记录表 (user_music_play_records)
-- ============================================

-- 用户+歌曲组合索引（查询用户是否播放过某首歌）
CREATE INDEX IF NOT EXISTS idx_user_song ON user_music_play_records(user_id, song_id);

-- 用户+创建时间组合索引（用户播放历史查询）
CREATE INDEX IF NOT EXISTS idx_user_created ON user_music_play_records(user_id, created_at DESC);

-- 歌曲+创建时间组合索引（歌曲热度统计）
CREATE INDEX IF NOT EXISTS idx_song_created ON user_music_play_records(song_id, created_at DESC);

-- ============================================
-- 8. 用户音乐提供商表 (user_music_providers)
-- ============================================

-- 用户+提供商组合索引（查询用户的提供商配置）
CREATE INDEX IF NOT EXISTS idx_user_provider ON user_music_providers(user_id, provider);

-- ============================================
-- 9. 用户书源表 (user_book_sources)
-- ============================================

-- 用户+书源组合索引
CREATE INDEX IF NOT EXISTS idx_user_source ON user_book_sources(user_id, source_id);

-- ============================================
-- 10. 图片生成任务表 (image_generation_tasks)
-- ============================================

-- 用户+状态+创建时间组合索引（用户任务列表查询）
CREATE INDEX IF NOT EXISTS idx_user_status_created ON image_generation_tasks(user_id, status, created_at DESC);

-- 状态+创建时间索引（任务队列查询）
CREATE INDEX IF NOT EXISTS idx_status_created ON image_generation_tasks(status, created_at);

-- 提示词索引（搜索功能）
CREATE INDEX IF NOT EXISTS idx_prompt ON image_generation_tasks(prompt(100));

-- ============================================
-- 11. 知识库条目表 (kb_entries)
-- ============================================

-- 用户+创建时间索引（用户知识库列表）
CREATE INDEX IF NOT EXISTS idx_user_created ON kb_entries(user_id, created_at DESC);

-- 用户+分类组合索引（按分类筛选）
CREATE INDEX IF NOT EXISTS idx_user_category ON kb_entries(user_id, category);

-- 标题索引（搜索功能）
CREATE INDEX IF NOT EXISTS idx_title ON kb_entries(title(100));

-- 标签索引（按标签筛选）
CREATE INDEX IF NOT EXISTS idx_tags ON kb_entries(tags(100));

-- ============================================
-- 12. 内容文章表 (content_articles)
-- ============================================

-- 用户+状态+发布时间组合索引（用户文章列表）
CREATE INDEX IF NOT EXISTS idx_user_status_published ON content_articles(user_id, status, published_at DESC);

-- 状态+发布时间索引（公开文章列表）
CREATE INDEX IF NOT EXISTS idx_status_published ON content_articles(status, published_at DESC);

-- 标题索引（搜索功能）
CREATE INDEX IF NOT EXISTS idx_title ON content_articles(title(100));

-- Slug索引（URL访问）
CREATE INDEX IF NOT EXISTS idx_slug ON content_articles(slug(100));

-- 分类索引（按分类筛选）
CREATE INDEX IF NOT EXISTS idx_category ON content_articles(category);

-- 自动化配置索引（自动化任务查询）
CREATE INDEX IF NOT EXISTS idx_automation_config ON content_articles(automation_config_id);

-- ============================================
-- 索引创建完成统计
-- ============================================

SELECT
    '✅ 索引创建完成！' AS status,
    COUNT(*) AS total_indexes,
    GROUP_CONCAT(DISTINCT TABLE_NAME) AS tables_optimized
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'website'
  AND INDEX_NAME LIKE 'idx_%';

-- 显示每个表的索引数量
SELECT
    TABLE_NAME,
    COUNT(*) AS index_count,
    GROUP_CONCAT(INDEX_NAME) AS indexes
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'website'
  AND INDEX_NAME LIKE 'idx_%'
GROUP BY TABLE_NAME
ORDER BY TABLE_NAME;
