-- =====================================================
-- 内置书源库（系统级书源）
-- 版本: 0012_builtin_book_sources.sql
-- 创建时间: 2026-11-23
-- 说明:
--   复用 user_book_source 表，使用保留 user_id = 0 存储站点级内置书源。
--   ImportedBookSourceReader 在用户源未命中时回退到 user_id = 0 的系统源，
--   BookSourceController.sources() 会把可执行的系统源合并进书源目录。
--
--   重要（2026-11-23 升级）：DSL 引擎已支持标准阅读书源规则
--   （class./id./tag./索引/链式/@text等/##正则替换/||回退 + 跟随跨域重定向），
--   19 个内置书源现由 classpath 资源 reading/builtin-sources.json 提供，
--   并由 DataInitializer.seedBuiltinBookSources() 在启动时幂等播种——
--   无需手动执行本 SQL。本文件仅保留为「单源手工补充」的参考模板。
-- =====================================================

-- 1. 品书斋（已实测：搜索/详情/目录/正文选择器均可用）
INSERT INTO user_book_source
    (user_id, source_key, source_name, source_url, source_type, enabled, status, definition_json, message, created_at, updated_at)
SELECT
    0,
    'sys-pinshuzhai',
    '品书斋（内置）',
    'https://www.pinshuzhai.com',
    'book',
    TRUE,
    'ready',
    JSON_OBJECT(
        'searchUrl', 'https://www.pinshuzhai.com/page/{{page}}?s={{key}}',
        'ruleSearch', JSON_OBJECT(
            'bookList', '.catdanlist ul li',
            'bookName', '.bookinfo h4 a',
            'bookUrl', '.bookinfo h4 a@href',
            'bookAuthor', '.bookinfo .author',
            'bookIntro', '.bookinfo p',
            'bookKind', '.bookinfo .cats a',
            'bookCover', ''
        ),
        'ruleBookInfo', JSON_OBJECT(
            'name', '.info h1, h1',
            'author', '.info .author, .author',
            'coverUrl', '.info img@src, .bookimg img@src',
            'intro', '.docspt',
            'category', '.catb a',
            'lastChapter', '.newzxzj .zjlist a',
            'status', '.info .status'
        ),
        'ruleToc', JSON_OBJECT(
            'chapterList', '.newzxzj .zjlist a',
            'chapterName', '.newzxzj .zjlist a',
            'chapterUrl', '.newzxzj .zjlist a@href'
        ),
        'ruleContent', JSON_OBJECT(
            'content', '#chapter',
            'title', '#chapter h1, h1'
        )
    ),
    '系统内置书源，服务端可直接解析',
    NOW(),
    NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM (
        SELECT source_key FROM user_book_source WHERE user_id = 0 AND source_key = 'sys-pinshuzhai'
    ) AS existing
);

-- 新增内置源的标准做法：把标准阅读书源 JSON 追加到
--   src/main/resources/reading/builtin-sources.json 数组即可，启动时自动播种。
-- 手工单源补充（user_id = 0，source_key 以 sys- 开头）仍可用下面的模板：
-- INSERT INTO user_book_source (...) SELECT 0, 'sys-xxx', ... WHERE NOT EXISTS (...);
