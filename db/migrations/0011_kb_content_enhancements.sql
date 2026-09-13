-- =====================================================
-- 知识库和内容工厂数据库迁移
-- 版本: 0011_kb_content_enhancements.sql
-- 创建时间: 2026-09-13
-- =====================================================

-- ============= 知识库增强 =============

-- 1. 知识库文档表
CREATE TABLE IF NOT EXISTS `kb_document` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `space_id` BIGINT NOT NULL COMMENT '空间ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `title` VARCHAR(500) NOT NULL COMMENT '文档标题',
  `content` LONGTEXT COMMENT '文档内容(Markdown)',
  `content_html` LONGTEXT COMMENT '文档内容(HTML)',
  `slug` VARCHAR(200) COMMENT 'URL slug',
  `parent_id` BIGINT COMMENT '父文档ID（支持层级结构）',
  `sort_order` INT DEFAULT 0 COMMENT '排序顺序',
  `status` VARCHAR(40) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT, PUBLISHED, ARCHIVED',
  `visibility` VARCHAR(40) NOT NULL DEFAULT 'PRIVATE' COMMENT '可见性: PRIVATE, INTERNAL, PUBLIC',
  `share_token` VARCHAR(64) COMMENT '公开分享token',
  `cover_image_url` VARCHAR(1000) COMMENT '封面图URL',
  `tags_json` VARCHAR(1000) COMMENT '标签JSON数组',
  `view_count` INT DEFAULT 0 COMMENT '浏览次数',
  `word_count` INT DEFAULT 0 COMMENT '字数',
  `reading_time_minutes` INT DEFAULT 0 COMMENT '预计阅读时间(分钟)',
  `last_edited_by` BIGINT COMMENT '最后编辑人ID',
  `published_at` DATETIME COMMENT '发布时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_space_status` (`space_id`, `status`),
  KEY `idx_user_status` (`user_id`, `status`),
  KEY `idx_slug` (`slug`),
  KEY `idx_share_token` (`share_token`),
  KEY `idx_parent` (`parent_id`),
  KEY `idx_published` (`published_at`),
  KEY `idx_sort` (`space_id`, `sort_order`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档';

-- 2. 知识库空间增强（添加新字段）
ALTER TABLE `kb_space`
ADD COLUMN `config_json` TEXT COMMENT '配置JSON' AFTER `doc_count`,
ADD COLUMN `is_default` TINYINT(1) DEFAULT 0 COMMENT '是否默认空间' AFTER `config_json`,
ADD COLUMN `document_count` INT DEFAULT 0 COMMENT '文档数量' AFTER `is_default`,
ADD INDEX `idx_user_default` (`user_id`, `is_default`);

-- ============= 内容工厂增强 =============

-- 3. 内容模板表
CREATE TABLE IF NOT EXISTS `content_template` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT COMMENT '用户ID（NULL表示系统模板）',
  `name` VARCHAR(200) NOT NULL COMMENT '模板名称',
  `description` VARCHAR(1000) COMMENT '模板描述',
  `category` VARCHAR(80) COMMENT '分类',
  `template_type` VARCHAR(40) NOT NULL DEFAULT 'CUSTOM' COMMENT '模板类型: SYSTEM, CUSTOM, SHARED',
  `content_template` LONGTEXT NOT NULL COMMENT '内容模板',
  `prompt_template` TEXT COMMENT 'AI提示词模板',
  `default_angle` TEXT COMMENT '默认角度',
  `default_audience` VARCHAR(500) COMMENT '默认受众',
  `default_tone` VARCHAR(500) COMMENT '默认语气',
  `cover_style` VARCHAR(500) COMMENT '封面样式',
  `layout_theme` VARCHAR(40) DEFAULT 'clean' COMMENT '布局主题',
  `image_mode` VARCHAR(40) DEFAULT 'generate' COMMENT '图片模式: generate, search, none',
  `tags_json` VARCHAR(1000) COMMENT '标签JSON数组',
  `sort_order` INT DEFAULT 0 COMMENT '排序',
  `is_active` TINYINT(1) DEFAULT 1 COMMENT '是否启用',
  `usage_count` INT DEFAULT 0 COMMENT '使用次数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_category` (`user_id`, `category`),
  KEY `idx_type_active` (`template_type`, `is_active`),
  KEY `idx_sort` (`sort_order`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容模板';

-- 4. 内容自动化任务表
CREATE TABLE IF NOT EXISTS `content_automation_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `task_name` VARCHAR(200) NOT NULL COMMENT '任务名称',
  `task_type` VARCHAR(40) NOT NULL COMMENT '任务类型: GENERATE_ARTICLE, PUBLISH_ARTICLE, etc.',
  `schedule_type` VARCHAR(40) NOT NULL COMMENT '调度类型: ONCE, HOURLY, DAILY, WEEKLY, MONTHLY, CUSTOM',
  `schedule_expression` VARCHAR(200) COMMENT '调度表达式',
  `template_id` BIGINT COMMENT '关联模板ID',
  `config_json` TEXT COMMENT '任务配置JSON',
  `status` VARCHAR(40) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING, RUNNING, SUCCESS, FAILED',
  `last_run_at` DATETIME COMMENT '上次运行时间',
  `next_run_at` DATETIME COMMENT '下次运行时间',
  `run_count` INT DEFAULT 0 COMMENT '运行次数',
  `success_count` INT DEFAULT 0 COMMENT '成功次数',
  `fail_count` INT DEFAULT 0 COMMENT '失败次数',
  `last_error` VARCHAR(1000) COMMENT '最后错误信息',
  `is_active` TINYINT(1) DEFAULT 1 COMMENT '是否启用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_active` (`user_id`, `is_active`),
  KEY `idx_next_run` (`next_run_at`, `is_active`),
  KEY `idx_status` (`status`, `updated_at`),
  KEY `idx_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容自动化任务';

-- 5. 内容文章表增强（如果需要添加模板ID关联）
ALTER TABLE `content_article`
ADD COLUMN `template_id` BIGINT COMMENT '使用的模板ID' AFTER `user_id`,
ADD INDEX `idx_template` (`template_id`);

-- ============= 初始数据 =============

-- 插入系统默认模板
INSERT INTO `content_template` (`user_id`, `name`, `description`, `category`, `template_type`, `content_template`, `prompt_template`, `layout_theme`, `image_mode`, `is_active`, `sort_order`)
VALUES
(NULL, '技术博客模板', '适合技术文章、教程、开发经验分享', '技术', 'SYSTEM',
'# {{title}}

## 背景

{{background}}

## 核心内容

{{main_content}}

## 总结

{{summary}}',
'你是一位资深技术博主，擅长用通俗易懂的语言讲解复杂的技术概念。请基于以下主题撰写一篇技术文章：{{topic}}',
'clean', 'generate', 1, 1),

(NULL, '产品分析模板', '适合产品评测、市场分析、行业观察', '产品', 'SYSTEM',
'# {{title}}

## 产品概述

{{overview}}

## 核心功能

{{features}}

## 优势与不足

{{pros_cons}}

## 市场定位

{{market_position}}

## 结论

{{conclusion}}',
'你是一位专业的产品分析师，擅长深入分析产品特性和市场定位。请分析以下产品：{{topic}}',
'clean', 'generate', 1, 2),

(NULL, '新闻报道模板', '适合新闻报道、事件分析、热点解读', '新闻', 'SYSTEM',
'# {{title}}

## 事件概述

{{summary}}

## 详细报道

{{details}}

## 影响分析

{{impact}}

## 相关背景

{{background}}',
'你是一位专业记者，擅长客观准确地报道新闻事件。请报道以下事件：{{topic}}',
'clean', 'search', 1, 3);

-- ============= 性能优化索引 =============

-- 为现有表添加缺失的索引
ALTER TABLE `content_article`
ADD INDEX IF NOT EXISTS `idx_user_status_updated` (`user_id`, `status`, `updated_at`),
ADD INDEX IF NOT EXISTS `idx_published` (`published_at`);

-- ============= 数据完整性约束 =============

-- 添加外键约束（可选，根据实际需求决定）
-- ALTER TABLE `kb_document` ADD CONSTRAINT `fk_doc_space` FOREIGN KEY (`space_id`) REFERENCES `kb_space`(`id`) ON DELETE CASCADE;
-- ALTER TABLE `content_automation_task` ADD CONSTRAINT `fk_task_template` FOREIGN KEY (`template_id`) REFERENCES `content_template`(`id`) ON DELETE SET NULL;

-- ============= 验证 =============

-- 查看创建的表
SHOW TABLES LIKE 'kb_%';
SHOW TABLES LIKE 'content_%';

-- 查看表结构
DESC kb_document;
DESC content_template;
DESC content_automation_task;

-- 查看索引
SHOW INDEX FROM kb_document;
SHOW INDEX FROM content_template;
SHOW INDEX FROM content_automation_task;

-- ============= 完成 =============
SELECT 'Migration 0011_kb_content_enhancements.sql completed successfully!' AS result;
