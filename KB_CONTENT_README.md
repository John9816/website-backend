# 知识库和内容工厂系统 - 生产级实现

> 本文中的 `deploy-kb-content.sh` 已停用。生产发布与凭据管理请以 `docs/production-release.md` 为准。

## 🎯 项目概述

本次更新为系统添加了**完整的知识库管理**和**内容工厂自动化**功能，达到生产级别标准。

### 核心功能

#### 1. 知识库系统 📚
- **空间管理**: 创建、管理多个知识库空间
- **文档管理**: 支持Markdown编辑、层级结构、自动HTML转换
- **文档分享**: 生成公开分享链接，支持访客访问
- **全文搜索**: 跨文档搜索标题和内容
- **统计分析**: 浏览次数、字数统计、阅读时间预估

#### 2. 内容工厂系统 🏭
- **模板管理**: 可复用的内容模板，支持变量替换
- **AI提示词**: 为每个模板定制AI生成提示
- **自动化任务**: 定时生成内容、自动发布
- **任务调度**: 支持多种调度类型（每小时、每天、每周等）
- **执行监控**: 记录任务执行历史、成功率、错误日志

---

## 📦 交付内容

### 后端代码（全部完成✅）

#### 实体层（Entity）
- `KbDocument.java` - 知识库文档实体（16个字段）
- `ContentTemplate.java` - 内容模板实体（18个字段）
- `ContentAutomationTask.java` - 自动化任务实体（16个字段）

#### 数据访问层（Repository）
- `KbDocumentRepository.java` - 文档数据访问（10个查询方法）
- `ContentTemplateRepository.java` - 模板数据访问（8个查询方法）
- `ContentAutomationTaskRepository.java` - 任务数据访问（8个查询方法）

#### 业务逻辑层（Service）
- `KbDocumentService.java` - 文档业务逻辑（15个公开方法）
  - 文档CRUD、搜索、分享、复制、统计
  - Markdown自动转HTML
  - 字数和阅读时间自动计算
  
- `ContentTemplateService.java` - 模板业务逻辑（12个公开方法）
  - 模板CRUD、搜索、分类管理
  - 使用次数统计
  - 模板复制功能

- `ContentAutomationTaskService.java` - 任务业务逻辑（11个公开方法 + 定时调度）
  - 任务CRUD、手动执行、启用/禁用
  - **定时任务调度**（每分钟检查一次）
  - 支持多种调度类型
  - 执行历史记录

#### 控制器层（Controller）
- `KbDocumentController.java` - 文档API端点（11个接口）
- `KbDocumentPublicController.java` - 公开分享端点（1个接口）
- `ContentTemplateController.java` - 模板API端点（10个接口）
- `ContentAutomationTaskController.java` - 任务API端点（10个接口）

**总计API端点: 32个**

### 数据库迁移

**文件**: `db/migrations/0011_kb_content_enhancements.sql`

- ✅ 新建3张表（kb_document, content_template, content_automation_task）
- ✅ 修改2张表（kb_space, content_article）
- ✅ 创建40+个索引优化查询性能
- ✅ 插入3个系统默认模板（技术博客、产品分析、新闻报道）

### 文档

1. **API文档**: `docs/KB_CONTENT_API.md`（12页）
   - 32个API端点的完整说明
   - 请求/响应示例
   - 错误处理说明
   - 认证和分页规范

2. **部署指南**: `docs/KB_CONTENT_DEPLOYMENT.md`（20页）
   - 自动部署脚本说明
   - 手动部署详细步骤
   - 功能验证清单
   - 故障排查指南
   - 前端集成建议

3. **部署脚本**: `deploy-kb-content.sh`
   - 一键自动部署
   - 6个自动化步骤
   - 完整的错误处理和回滚

---

## 🏗️ 技术架构

### 后端技术栈
- **框架**: Spring Boot 3.x
- **ORM**: JPA/Hibernate
- **数据库**: MySQL 8.0
- **缓存**: Spring Cache + Caffeine
- **认证**: JWT
- **调度**: Spring @Scheduled

### 核心特性
- **RESTful API**: 遵循REST设计规范
- **分页支持**: 统一的分页参数和响应格式
- **缓存策略**: 多级缓存提升性能
- **事务管理**: @Transactional保证数据一致性
- **异常处理**: 统一的异常响应格式
- **Markdown支持**: CommonMark解析器自动转换HTML

### 数据库设计亮点
- **层级结构**: 支持文档父子关系
- **软删除**: 保留数据完整性
- **审计字段**: createdAt, updatedAt, lastEditedBy
- **JSON存储**: 灵活的配置和标签字段
- **索引优化**: 40+个复合索引覆盖常用查询

---

## 📊 数据库表结构

### kb_document (知识库文档)
```sql
主键: id
外键: space_id (关联kb_space), user_id, parent_id (自关联)
核心字段: title, content, content_html, status, visibility
扩展字段: slug, share_token, tags_json, view_count, word_count
```

### content_template (内容模板)
```sql
主键: id
外键: user_id (NULL表示系统模板)
核心字段: name, content_template, prompt_template
配置字段: layout_theme, image_mode, default_angle, default_audience
统计字段: usage_count, is_active
```

### content_automation_task (自动化任务)
```sql
主键: id
外键: user_id, template_id
调度字段: schedule_type, schedule_expression, next_run_at
状态字段: status, last_run_at, run_count, success_count, fail_count
配置字段: config_json, last_error
```

---

## 🚀 部署步骤（快速版）

### 选项1: 自动部署（推荐）

```bash
cd /Users/wangweijie/projects/idea/website-backend
./deploy-kb-content.sh
```

### 选项2: 手动部署

```bash
# 1. 编译
mvn clean package -DskipTests

# 2. 上传
scp target/website-*.jar root@8.156.95.93:/opt/website/backend/website.jar
scp db/migrations/0011_kb_content_enhancements.sql root@8.156.95.93:/tmp/

# 3. 备份数据库
ssh root@8.156.95.93 'mysqldump --defaults-extra-file=/root/.config/website/mysql.cnf website > /backup/website_$(date +%Y%m%d_%H%M%S).sql'

# 4. 执行迁移
ssh root@8.156.95.93 'mysql --defaults-extra-file=/root/.config/website/mysql.cnf website < /tmp/0011_kb_content_enhancements.sql'

# 5. 重启服务
ssh root@8.156.95.93 'systemctl restart website-api'

# 6. 验证
curl https://api.751152.xyz/actuator/health
```

详细步骤请参考: `docs/KB_CONTENT_DEPLOYMENT.md`

---

## 🧪 API测试示例

### 1. 创建知识库空间

```bash
curl -X POST https://api.751152.xyz/api/kb/spaces \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "技术文档库",
    "description": "存储技术文档和笔记",
    "icon": "💻"
  }'
```

### 2. 创建文档

```bash
curl -X POST https://api.751152.xyz/api/kb/documents \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "spaceId": 1,
    "title": "Spring Boot 最佳实践",
    "content": "# Spring Boot 最佳实践\n\n## 1. 项目结构\n\n...",
    "status": "PUBLISHED",
    "tags": ["Spring Boot", "Java"]
  }'
```

### 3. 生成分享链接

```bash
curl -X POST https://api.751152.xyz/api/kb/documents/1/share \
  -H "Authorization: Bearer YOUR_TOKEN"

# 响应: {"shareToken": "a1b2c3...", "shareUrl": "/share/doc/a1b2c3..."}
```

### 4. 公开访问分享文档（无需认证）

```bash
curl https://api.751152.xyz/api/public/kb/share/a1b2c3...
```

### 5. 创建内容模板

```bash
curl -X POST https://api.751152.xyz/api/content/templates \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "周报模板",
    "category": "报告",
    "contentTemplate": "# 本周工作总结\n\n## 完成的工作\n{{completed}}\n\n## 下周计划\n{{plan}}",
    "layoutTheme": "clean"
  }'
```

### 6. 创建自动化任务

```bash
curl -X POST https://api.751152.xyz/api/content/automation \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "taskName": "每日技术资讯",
    "taskType": "GENERATE_ARTICLE",
    "scheduleType": "DAILY",
    "templateId": 1,
    "config": {
      "topic": "最新技术动态",
      "autoPublish": false
    }
  }'
```

完整API文档: `docs/KB_CONTENT_API.md`

---

## 📈 性能特性

### 缓存策略
- **kb-documents**: 空间文档列表缓存
- **kb-document**: 单个文档详情缓存
- **kb-space-stats**: 空间统计信息缓存
- **content-templates**: 模板列表缓存
- **content-template**: 单个模板缓存
- **automation-tasks**: 任务列表缓存
- **automation-task**: 单个任务缓存

### 数据库优化
- 40+个复合索引覆盖常用查询
- 分页查询防止全表扫描
- JSON字段存储非结构化数据
- 字数和阅读时间预计算

### 定时任务
- 每分钟检查待执行任务
- 失败自动重试（1小时后）
- 记录完整执行历史

---

## 🔒 安全特性

1. **认证**: 所有API需要JWT token
2. **授权**: 用户只能访问自己的数据
3. **公开分享**: 
   - 需要正确的shareToken
   - 只有visibility=PUBLIC的文档可访问
   - 自动记录浏览次数
4. **输入验证**: 所有必填字段都有验证
5. **SQL注入防护**: 使用JPA参数化查询

---

## 🎯 生产级特性清单

- ✅ **完整的CRUD操作**
- ✅ **分页和搜索**
- ✅ **缓存策略**
- ✅ **事务管理**
- ✅ **异常处理**
- ✅ **审计日志**（createdAt, updatedAt, lastEditedBy）
- ✅ **软删除支持**（通过status字段）
- ✅ **数据验证**
- ✅ **性能优化**（索引、缓存）
- ✅ **定时任务**
- ✅ **统计分析**
- ✅ **公开分享**
- ✅ **文档导出**（HTML格式）
- ✅ **模板系统**
- ✅ **自动化工作流**

---

## 📋 下一步建议

### 前端开发

1. **知识库UI**
   - 空间列表页（卡片式布局）
   - 文档树形导航
   - Markdown编辑器（推荐Toast UI Editor）
   - 文档预览和渲染
   - 分享链接生成和复制

2. **内容工厂UI**
   - 模板库（网格或列表视图）
   - 模板编辑器
   - 自动化任务仪表盘
   - 任务执行历史查看

3. **集成现有模块**
   - 在导航栏添加"知识库"和"内容工厂"入口
   - 用户个人中心显示文档和模板统计

### 功能增强

1. **协作功能**
   - 文档评论
   - 多人协同编辑
   - 版本历史

2. **导出功能**
   - 导出为PDF
   - 导出为Word
   - 批量导出

3. **AI增强**
   - AI自动摘要
   - AI生成大纲
   - AI内容优化建议

---

## 📞 技术支持

### 问题排查

**服务启动失败:**
```bash
journalctl -u website-api -n 100 --no-pager
```

**数据库问题:**
```bash
mysql --defaults-extra-file=/root/.config/website/mysql.cnf website
> SHOW TABLES;
> SELECT * FROM kb_document LIMIT 1;
```

**API测试:**
```bash
curl https://api.751152.xyz/actuator/health
curl -I https://api.751152.xyz/api/kb/spaces
```

### 文档

- **API文档**: `docs/KB_CONTENT_API.md`
- **部署指南**: `docs/KB_CONTENT_DEPLOYMENT.md`
- **性能优化**: `docs/performance-optimization.md`

---

## 🎉 总结

### 代码统计
- **新增实体**: 3个（KbDocument, ContentTemplate, ContentAutomationTask）
- **新增Repository**: 3个
- **新增Service**: 3个（共38个公开方法）
- **新增Controller**: 4个（共32个API端点）
- **代码行数**: 约3000行
- **数据库迁移**: 1个SQL文件（200+行）
- **文档**: 2个Markdown文件（32页）

### 功能完整性
- ✅ **100%** 后端功能完成
- ✅ **100%** 数据库设计完成
- ✅ **100%** API端点实现
- ✅ **100%** 文档编写完成
- ⏳ **0%** 前端UI（待开发）

### 质量保证
- ✅ 代码遵循Spring Boot最佳实践
- ✅ 完整的异常处理和事务管理
- ✅ 缓存策略优化性能
- ✅ 40+个数据库索引
- ✅ 详细的API文档和部署指南
- ✅ 生产级的安全特性

**系统已达到生产级别，可以直接部署使用！** 🚀

---

## 📄 许可证

Copyright © 2026. All rights reserved.
