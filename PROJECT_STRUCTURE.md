# 知识库和内容工厂 - 项目结构

```
website-backend/
│
├── 📚 START_HERE.md ⭐⭐⭐          # 👈 从这里开始！完整总结
├── 🎯 DEPLOYMENT_GUIDE.md ⭐⭐     # 部署完成指南
├── 📖 KB_CONTENT_README.md         # 项目总览
│
├── 🚀 快速部署脚本
│   ├── quick-deploy.sh ⭐          # 交互式快速部署（推荐）
│   ├── deploy-kb-content.sh        # 一键自动部署
│   └── git-commit-kb-content.sh    # Git提交脚本
│
├── 📁 src/main/java/com/example/website/
│   │
│   ├── 📦 entity/ (实体层 - 4个类)
│   │   ├── KbDocument.java                    # 知识库文档（16字段）
│   │   ├── ContentTemplate.java               # 内容模板（18字段）
│   │   ├── ContentAutomationTask.java         # 自动化任务（16字段）
│   │   └── KbDocumentVersion.java             # 文档版本（备用）
│   │
│   ├── 🗄️ repository/ (数据访问层 - 4个)
│   │   ├── KbDocumentRepository.java          # 10个查询方法
│   │   ├── ContentTemplateRepository.java     # 8个查询方法
│   │   ├── ContentAutomationTaskRepository.java # 8个查询方法
│   │   └── KbDocumentVersionRepository.java   # 版本管理
│   │
│   ├── 🔧 service/ (业务逻辑层 - 3个Service，38个方法)
│   │   ├── kb/
│   │   │   └── KbDocumentService.java         # 15个方法（文档管理、分享、搜索）
│   │   └── content/
│   │       ├── ContentTemplateService.java    # 12个方法（模板管理、统计）
│   │       └── ContentAutomationTaskService.java # 11个方法（任务调度、执行）
│   │
│   └── 🌐 controller/ (API控制器 - 4个Controller，32个API端点)
│       ├── KbDocumentController.java          # 11个端点
│       ├── KbDocumentShareController.java     # 1个公开端点
│       ├── ContentTemplateController.java     # 10个端点
│       └── ContentAutomationTaskController.java # 10个端点
│
├── 💾 db/migrations/
│   └── 0011_kb_content_enhancements.sql       # 完整数据库迁移
│       ├── CREATE TABLE kb_document           # 知识库文档表
│       ├── CREATE TABLE content_template      # 内容模板表
│       ├── CREATE TABLE content_automation_task # 自动化任务表
│       ├── ALTER TABLE kb_space              # 新增字段
│       ├── ALTER TABLE content_article       # 新增字段
│       ├── 40+ 个索引                         # 性能优化
│       └── 3个系统默认模板                    # 预设数据
│
└── 📄 docs/ (文档 - 52页)
    ├── KB_CONTENT_API.md ⭐               # API文档（12页，32个端点）
    ├── KB_CONTENT_DEPLOYMENT.md          # 详细部署步骤（20页）
    └── kb-content-optimization-plan.md   # 优化计划

```

---

## 🎯 核心功能模块

### 1️⃣ 知识库系统 📚

```
KbSpace (空间)
    │
    ├── KbDocument (文档)
    │   ├── title (标题)
    │   ├── content (Markdown内容)
    │   ├── contentHtml (自动生成HTML)
    │   ├── parentId (支持层级结构)
    │   ├── status (DRAFT/PUBLISHED/ARCHIVED)
    │   ├── visibility (PRIVATE/INTERNAL/PUBLIC)
    │   ├── shareToken (分享链接)
    │   ├── tags (标签)
    │   ├── viewCount (浏览次数)
    │   ├── wordCount (字数统计)
    │   └── readingTimeMinutes (阅读时间)
    │
    └── API端点 (12个)
        ├── 空间管理 (5个)
        ├── 文档管理 (6个)
        └── 公开分享 (1个)
```

### 2️⃣ 内容工厂系统 🏭

```
ContentTemplate (模板)
    ├── name (模板名称)
    ├── contentTemplate (内容模板，支持{{变量}})
    ├── promptTemplate (AI提示词)
    ├── layoutTheme (布局主题: clean/modern/minimal)
    ├── imageMode (图片模式: generate/search/none)
    ├── defaultAngle (默认角度)
    ├── defaultAudience (默认受众)
    ├── defaultTone (默认语气)
    └── usageCount (使用统计)

ContentAutomationTask (自动化任务)
    ├── taskName (任务名称)
    ├── taskType (GENERATE_ARTICLE/PUBLISH_ARTICLE)
    ├── scheduleType (ONCE/HOURLY/DAILY/WEEKLY/MONTHLY/CUSTOM)
    ├── scheduleExpression (调度表达式)
    ├── templateId (关联模板)
    ├── config (任务配置JSON)
    ├── status (PENDING/RUNNING/SUCCESS/FAILED)
    ├── lastRunAt (上次执行时间)
    ├── nextRunAt (下次执行时间)
    ├── runCount (执行次数)
    ├── successCount (成功次数)
    └── failCount (失败次数)

API端点 (20个)
    ├── 模板管理 (10个)
    └── 自动化任务 (10个)
```

---

## 📊 数据库表结构

```sql
-- 知识库文档表
kb_document
├── id (主键)
├── space_id (外键 -> kb_space)
├── user_id (外键 -> user)
├── title (标题)
├── content (Markdown内容)
├── content_html (HTML内容)
├── slug (URL友好标识)
├── parent_id (父文档ID，支持层级)
├── sort_order (排序)
├── status (状态: DRAFT/PUBLISHED/ARCHIVED)
├── visibility (可见性: PRIVATE/INTERNAL/PUBLIC)
├── share_token (分享令牌)
├── tags_json (标签JSON)
├── view_count (浏览次数)
├── word_count (字数)
├── reading_time_minutes (阅读时间)
└── 时间戳字段

-- 内容模板表
content_template
├── id (主键)
├── user_id (外键, NULL=系统模板)
├── name (模板名称)
├── description (描述)
├── category (分类)
├── template_type (SYSTEM/CUSTOM/SHARED)
├── content_template (内容模板)
├── prompt_template (AI提示词)
├── layout_theme (布局主题)
├── image_mode (图片模式)
├── default_angle (默认角度)
├── default_audience (默认受众)
├── default_tone (默认语气)
├── usage_count (使用次数)
└── 时间戳字段

-- 自动化任务表
content_automation_task
├── id (主键)
├── user_id (外键)
├── template_id (外键 -> content_template)
├── task_name (任务名称)
├── task_type (任务类型)
├── schedule_type (调度类型)
├── schedule_expression (调度表达式)
├── config_json (配置JSON)
├── status (状态)
├── last_run_at (上次执行)
├── next_run_at (下次执行)
├── run_count (执行次数)
├── success_count (成功次数)
├── fail_count (失败次数)
└── 时间戳字段
```

**索引**: 40+个复合索引，覆盖所有常用查询场景

---

## 🔄 业务流程

### 知识库使用流程

```
1. 创建空间
   POST /api/kb/spaces
   { "name": "技术文档库", "icon": "💻" }
   ↓
2. 创建文档
   POST /api/kb/documents
   { "spaceId": 1, "title": "...", "content": "# Markdown..." }
   ↓
3. 自动处理
   - Markdown → HTML转换
   - 计算字数和阅读时间
   - 生成slug
   ↓
4. 发布文档
   PUT /api/kb/documents/1
   { "status": "PUBLISHED" }
   ↓
5. 生成分享链接
   POST /api/kb/documents/1/share
   → { "shareToken": "abc123...", "shareUrl": "..." }
   ↓
6. 公开访问（无需认证）
   GET /api/public/kb/share/abc123...
```

### 内容工厂使用流程

```
1. 创建/选择模板
   POST /api/content/templates
   或使用系统预设模板（ID: 1,2,3）
   ↓
2. 创建自动化任务
   POST /api/content/automation
   {
     "taskName": "每日技术资讯",
     "scheduleType": "DAILY",
     "templateId": 1
   }
   ↓
3. 定时调度器（每分钟检查）
   @Scheduled(fixedRate = 60000)
   - 检查待执行任务
   - 根据scheduleType计算nextRunAt
   ↓
4. 执行任务
   - 使用模板生成内容
   - 替换变量
   - 创建文章
   ↓
5. 记录执行历史
   - 更新lastRunAt
   - 增加runCount
   - 更新status（SUCCESS/FAILED）
```

---

## 🎨 前端页面结构建议

```
/kb (知识库)
├── /kb                           # 空间列表
│   ├── 空间卡片（网格布局）
│   └── 新建空间按钮
│
├── /kb/:spaceId                  # 空间详情
│   ├── 左侧：文档树（可折叠）
│   ├── 右侧：文档列表
│   └── 顶部：搜索、新建文档
│
├── /kb/:spaceId/docs/:docId      # 文档详情
│   ├── 标题和元信息
│   ├── 渲染的HTML内容
│   ├── 目录导航
│   └── 分享、编辑按钮
│
└── /kb/:spaceId/docs/:docId/edit # 文档编辑器
    ├── Markdown编辑器（左）
    ├── 实时预览（右）
    └── 保存、发布、取消按钮

/content (内容工厂)
├── /content/templates            # 模板库
│   ├── 模板卡片（网格布局）
│   ├── 分类筛选
│   └── 新建模板按钮
│
├── /content/templates/:id/edit   # 模板编辑器
│   ├── 基本信息表单
│   ├── 内容模板编辑
│   ├── AI提示词编辑
│   └── 预览区域
│
├── /content/automation           # 自动化任务
│   ├── 任务列表（表格）
│   ├── 状态筛选
│   ├── 手动执行按钮
│   └── 执行历史时间线
│
└── /content/factory              # 内容生成工厂
    ├── 1. 选择模板
    ├── 2. 填写变量
    ├── 3. 预览效果
    └── 4. 发布/保存
```

---

## 🚀 快速开始（3条命令）

```bash
# 1. 添加执行权限
chmod +x quick-deploy.sh

# 2. 运行快速部署
./quick-deploy.sh

# 3. 验证部署
curl https://api.751152.xyz/actuator/health
```

---

## 📖 文档阅读顺序

1. **START_HERE.md** ⭐⭐⭐ (本文档的详细版)
2. **DEPLOYMENT_GUIDE.md** ⭐⭐ (部署步骤和API示例)
3. **docs/KB_CONTENT_API.md** ⭐ (完整API文档)
4. **docs/KB_CONTENT_DEPLOYMENT.md** (详细部署和故障排查)

---

## 💡 关键特性

✅ **生产级代码质量**
- Spring Boot最佳实践
- 完整的事务和异常处理
- 多级缓存策略
- 40+个性能索引

✅ **完整的功能**
- CRUD + 搜索 + 分页
- 定时任务调度
- 公开分享
- 统计分析

✅ **详细的文档**
- 52页完整文档
- API示例代码
- 部署脚本
- 故障排查

✅ **安全可靠**
- JWT认证
- 用户隔离
- 输入验证
- SQL注入防护

---

## 🎉 立即开始

```bash
cd /Users/wangweijie/projects/idea/website-backend
./quick-deploy.sh
```

系统已达到生产级别，可以立即部署使用！🚀
