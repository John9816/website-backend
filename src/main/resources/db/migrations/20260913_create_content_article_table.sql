-- 内容工厂：文章表
CREATE TABLE IF NOT EXISTS content_article (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '创建者ID',
    title VARCHAR(500) NOT NULL COMMENT '文章标题',
    digest VARCHAR(500) COMMENT '文章摘要',
    content_markdown LONGTEXT COMMENT 'Markdown格式正文',
    content_html LONGTEXT NOT NULL COMMENT 'HTML格式正文',
    cover_prompt VARCHAR(2000) COMMENT '封面图提示词',
    cover_image_url VARCHAR(1000) COMMENT '封面图URL',
    inline_images_json LONGTEXT COMMENT '内联图片JSON',
    topics_json LONGTEXT COMMENT '选题JSON',
    tags_json VARCHAR(2000) COMMENT '标签JSON',
    risk_tips_json VARCHAR(2000) COMMENT '风险提示JSON',
    model VARCHAR(100) COMMENT '使用的AI模型',
    category VARCHAR(80) COMMENT '文章分类',
    layout_theme VARCHAR(40) COMMENT '布局主题',
    image_mode VARCHAR(40) COMMENT '图片模式',
    automation_json LONGTEXT COMMENT '自动化信息JSON',
    plan_json LONGTEXT COMMENT '文章计划JSON',
    evidence_json LONGTEXT COMMENT '证据来源JSON',
    review_json LONGTEXT COMMENT '审稿信息JSON',
    quality_score INT COMMENT '质量评分',
    status VARCHAR(40) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT, WECHAT_DRAFT, PUBLISHED',
    wechat_media_id VARCHAR(200) COMMENT '微信素材ID',
    wechat_publish_id VARCHAR(200) COMMENT '微信发布ID',
    wechat_url VARCHAR(1000) COMMENT '微信文章URL',
    error_message VARCHAR(1000) COMMENT '错误信息',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_user_updated (user_id, updated_at, id),
    INDEX idx_user_category_updated (user_id, category, updated_at),
    INDEX idx_user_status (user_id, status),
    INDEX idx_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容工厂文章表';

-- 内容工厂：文章模板表
CREATE TABLE IF NOT EXISTS content_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT COMMENT '创建者ID，NULL表示系统模板',
    name VARCHAR(200) NOT NULL COMMENT '模板名称',
    description VARCHAR(1000) COMMENT '模板描述',
    category VARCHAR(80) COMMENT '适用分类',
    template_type VARCHAR(40) NOT NULL DEFAULT 'CUSTOM' COMMENT '模板类型: SYSTEM, CUSTOM',
    content_template LONGTEXT NOT NULL COMMENT '内容模板（支持变量）',
    prompt_template TEXT COMMENT '提示词模板',
    default_angle TEXT COMMENT '默认角度',
    default_audience VARCHAR(500) COMMENT '默认受众',
    default_tone VARCHAR(500) COMMENT '默认语气',
    cover_style VARCHAR(500) COMMENT '封面风格',
    layout_theme VARCHAR(40) DEFAULT 'clean' COMMENT '布局主题',
    image_mode VARCHAR(40) DEFAULT 'generate' COMMENT '图片模式',
    tags_json VARCHAR(1000) COMMENT '标签JSON',
    sort_order INT DEFAULT 0 COMMENT '排序',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    usage_count INT DEFAULT 0 COMMENT '使用次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_user_category (user_id, category),
    INDEX idx_type_active (template_type, is_active),
    INDEX idx_sort (sort_order, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容工厂模板表';

-- 内容工厂：自动化任务表
CREATE TABLE IF NOT EXISTS content_automation_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '创建者ID',
    task_name VARCHAR(200) NOT NULL COMMENT '任务名称',
    task_type VARCHAR(40) NOT NULL COMMENT '任务类型: GENERATE, PUBLISH, BATCH',
    schedule_type VARCHAR(40) NOT NULL COMMENT '调度类型: ONCE, DAILY, WEEKLY, CRON',
    schedule_expression VARCHAR(200) COMMENT 'Cron表达式或时间配置',
    template_id BIGINT COMMENT '使用的模板ID',
    config_json TEXT COMMENT '任务配置JSON',
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING, RUNNING, COMPLETED, FAILED',
    last_run_at DATETIME COMMENT '上次运行时间',
    next_run_at DATETIME COMMENT '下次运行时间',
    run_count INT DEFAULT 0 COMMENT '运行次数',
    success_count INT DEFAULT 0 COMMENT '成功次数',
    fail_count INT DEFAULT 0 COMMENT '失败次数',
    last_error VARCHAR(1000) COMMENT '最后错误',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_user_active (user_id, is_active),
    INDEX idx_next_run (next_run_at, is_active),
    INDEX idx_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容工厂自动化任务表';

-- 插入系统默认模板
INSERT INTO content_template (name, description, category, template_type, content_template, prompt_template,
    default_angle, default_audience, default_tone, cover_style, layout_theme, sort_order, is_active)
VALUES
    ('科技热点解读', '适合科技互联网热点的快速解读模板', '科技 / 互联网', 'SYSTEM',
     '# {title}\n\n{opening}\n\n## 事件回顾\n\n{event_review}\n\n## 信息差分析\n\n{analysis}\n\n## 普通人该注意什么\n\n{tips}\n\n---\n\n{closing}',
     '围绕「{topic}」写一篇科技热点解读，重点讲清楚信息差和对普通人的影响',
     '从信息差和普通人的角度切入，讲清楚事件背后的利益链和影响',
     '想快速看懂热点、不想被标题带节奏的读者',
     '像朋友聊天，口语化，有判断但不端着',
     '科技信息卡片，简洁现代，蓝色调',
     'clean', 100, TRUE),

    ('教育职场攻略', '适合教育和职场相关内容的模板', '教育 / 职场', 'SYSTEM',
     '# {title}\n\n{opening}\n\n## 现状扫描\n\n{situation}\n\n## 关键选择\n\n{choices}\n\n## 行动建议\n\n{action}\n\n---\n\n{closing}',
     '围绕「{topic}」写一篇教育职场攻略，给出实用建议',
     '从升学、就业、转行和职场选择里的具体成本切入',
     '关注教育和职场发展的读者',
     '务实、不鸡汤、给具体建议',
     '教育场景插画，温暖色调',
     'warm', 90, TRUE),

    ('财经信息差', '适合财经金融相关话题的模板', '财政 / 金融', 'SYSTEM',
     '# {title}\n\n{opening}\n\n## 政策解读\n\n{policy}\n\n## 钱包影响\n\n{impact}\n\n## 应对策略\n\n{strategy}\n\n---\n\n{closing}',
     '围绕「{topic}」写一篇财经解读，讲清楚和钱包的关系',
     '翻译政策和市场新闻里的关键信息差，落到钱包和决策影响',
     '关注个人财务和投资的普通人',
     '专业但不艰涩，用大白话讲清楚',
     '财经数据图表，稳重配色',
     'magazine', 80, TRUE);
