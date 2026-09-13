# 知识库和内容工厂 API 文档

版本: v1.0  
更新时间: 2026-09-13

## 目录

- [知识库 API](#知识库-api)
  - [空间管理](#空间管理)
  - [文档管理](#文档管理)
- [内容工厂 API](#内容工厂-api)
  - [模板管理](#模板管理)
  - [自动化任务](#自动化任务)

---

## 知识库 API

### 空间管理

#### 1. 创建空间

```http
POST /api/kb/spaces
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "我的知识库",
  "description": "个人知识管理空间",
  "icon": "📚",
  "isPublic": false
}
```

**响应:**
```json
{
  "id": 1,
  "userId": 100,
  "name": "我的知识库",
  "description": "个人知识管理空间",
  "icon": "📚",
  "isPublic": false,
  "isDefault": false,
  "docCount": 0,
  "documentCount": 0,
  "config": null,
  "createdAt": "2026-09-13T10:00:00",
  "updatedAt": "2026-09-13T10:00:00"
}
```

#### 2. 获取空间列表

```http
GET /api/kb/spaces?page=0&size=20
Authorization: Bearer {token}
```

**响应:**
```json
{
  "content": [
    {
      "id": 1,
      "name": "我的知识库",
      "icon": "📚",
      "docCount": 10,
      "isPublic": false,
      "updatedAt": "2026-09-13T10:00:00"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "size": 20,
  "number": 0
}
```

#### 3. 获取空间详情

```http
GET /api/kb/spaces/{spaceId}
Authorization: Bearer {token}
```

#### 4. 更新空间

```http
PUT /api/kb/spaces/{spaceId}
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "更新后的名称",
  "description": "更新后的描述"
}
```

#### 5. 删除空间

```http
DELETE /api/kb/spaces/{spaceId}
Authorization: Bearer {token}
```

---

### 文档管理

#### 1. 创建文档

```http
POST /api/kb/documents
Authorization: Bearer {token}
Content-Type: application/json

{
  "spaceId": 1,
  "title": "Spring Boot 最佳实践",
  "content": "# Spring Boot 最佳实践\n\n## 介绍\n\n...",
  "parentId": null,
  "sortOrder": 0,
  "status": "DRAFT",
  "visibility": "PRIVATE",
  "coverImageUrl": "https://example.com/cover.jpg",
  "tags": ["Spring Boot", "Java", "后端"]
}
```

**字段说明:**
- `spaceId`: 空间ID（必填）
- `title`: 文档标题（必填）
- `content`: Markdown 格式内容
- `parentId`: 父文档ID（可选，支持层级结构）
- `sortOrder`: 排序顺序（默认0）
- `status`: 状态 - DRAFT（草稿）, PUBLISHED（已发布）, ARCHIVED（已归档）
- `visibility`: 可见性 - PRIVATE（私有）, INTERNAL（内部）, PUBLIC（公开）
- `coverImageUrl`: 封面图URL
- `tags`: 标签数组

**响应:**
```json
{
  "id": 1,
  "spaceId": 1,
  "userId": 100,
  "title": "Spring Boot 最佳实践",
  "content": "# Spring Boot 最佳实践...",
  "contentHtml": "<h1>Spring Boot 最佳实践</h1>...",
  "slug": "spring-boot-zui-jia-shi-jian-a1b2c3",
  "parentId": null,
  "sortOrder": 0,
  "status": "DRAFT",
  "visibility": "PRIVATE",
  "shareToken": null,
  "coverImageUrl": "https://example.com/cover.jpg",
  "tags": ["Spring Boot", "Java", "后端"],
  "viewCount": 0,
  "wordCount": 1500,
  "readingTimeMinutes": 4,
  "lastEditedBy": 100,
  "publishedAt": null,
  "createdAt": "2026-09-13T10:00:00",
  "updatedAt": "2026-09-13T10:00:00"
}
```

#### 2. 获取文档列表

```http
GET /api/kb/documents?spaceId=1&status=PUBLISHED&page=0&size=20
Authorization: Bearer {token}
```

**查询参数:**
- `spaceId`: 空间ID（必填）
- `status`: 过滤状态（可选）
- `page`: 页码（默认0）
- `size`: 每页数量（默认20）

#### 3. 获取子文档

```http
GET /api/kb/documents/children?spaceId=1&parentId=10&status=PUBLISHED
Authorization: Bearer {token}
```

**查询参数:**
- `spaceId`: 空间ID（必填）
- `parentId`: 父文档ID（可选，null表示获取根文档）
- `status`: 过滤状态（可选）

#### 4. 搜索文档

```http
GET /api/kb/documents/search?spaceId=1&keyword=Spring&page=0&size=20
Authorization: Bearer {token}
```

#### 5. 获取文档详情

```http
GET /api/kb/documents/{documentId}
Authorization: Bearer {token}
```

#### 6. 更新文档

```http
PUT /api/kb/documents/{documentId}
Authorization: Bearer {token}
Content-Type: application/json

{
  "title": "更新后的标题",
  "content": "更新后的内容...",
  "status": "PUBLISHED"
}
```

#### 7. 删除文档

```http
DELETE /api/kb/documents/{documentId}
Authorization: Bearer {token}
```

#### 8. 生成分享链接

```http
POST /api/kb/documents/{documentId}/share
Authorization: Bearer {token}
```

**响应:**
```json
{
  "shareToken": "a1b2c3d4e5f6g7h8",
  "shareUrl": "/share/doc/a1b2c3d4e5f6g7h8"
}
```

#### 9. 复制文档

```http
POST /api/kb/documents/{documentId}/duplicate
Authorization: Bearer {token}
```

#### 10. 获取热门文档

```http
GET /api/kb/documents/popular?page=0&size=10
Authorization: Bearer {token}
```

#### 11. 获取统计信息

```http
GET /api/kb/documents/statistics?spaceId=1
Authorization: Bearer {token}
```

**响应:**
```json
{
  "totalDocuments": 50,
  "publishedDocuments": 30,
  "draftDocuments": 20
}
```

#### 12. 公开访问分享文档

```http
GET /api/public/kb/share/{shareToken}
```

无需认证，直接访问公开分享的文档。

---

## 内容工厂 API

### 模板管理

#### 1. 创建模板

```http
POST /api/content/templates
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "技术博客模板",
  "description": "适合技术文章、教程、开发经验分享",
  "category": "技术",
  "templateType": "CUSTOM",
  "contentTemplate": "# {{title}}\n\n## 背景\n\n{{background}}...",
  "promptTemplate": "你是一位资深技术博主...",
  "defaultAngle": "技术深度解析",
  "defaultAudience": "技术开发者",
  "defaultTone": "专业、易懂",
  "coverStyle": "tech-blue",
  "layoutTheme": "clean",
  "imageMode": "generate",
  "tags": ["技术", "教程"],
  "sortOrder": 0,
  "isActive": true
}
```

**字段说明:**
- `name`: 模板名称（必填）
- `description`: 模板描述
- `category`: 分类
- `templateType`: 模板类型 - SYSTEM（系统）, CUSTOM（自定义）, SHARED（共享）
- `contentTemplate`: 内容模板，支持变量 {{var}}
- `promptTemplate`: AI提示词模板
- `defaultAngle`: 默认角度
- `defaultAudience`: 默认受众
- `defaultTone`: 默认语气
- `coverStyle`: 封面样式
- `layoutTheme`: 布局主题（clean, modern, minimal）
- `imageMode`: 图片模式 - generate（生成）, search（搜索）, none（无）
- `tags`: 标签数组
- `sortOrder`: 排序顺序
- `isActive`: 是否启用

**响应:**
```json
{
  "id": 1,
  "userId": 100,
  "name": "技术博客模板",
  "description": "适合技术文章、教程、开发经验分享",
  "category": "技术",
  "templateType": "CUSTOM",
  "contentTemplate": "# {{title}}...",
  "promptTemplate": "你是一位资深技术博主...",
  "defaultAngle": "技术深度解析",
  "defaultAudience": "技术开发者",
  "defaultTone": "专业、易懂",
  "coverStyle": "tech-blue",
  "layoutTheme": "clean",
  "imageMode": "generate",
  "tags": ["技术", "教程"],
  "sortOrder": 0,
  "isActive": true,
  "usageCount": 0,
  "createdAt": "2026-09-13T10:00:00",
  "updatedAt": "2026-09-13T10:00:00"
}
```

#### 2. 获取模板列表

```http
GET /api/content/templates?onlyActive=true&page=0&size=20
Authorization: Bearer {token}
```

#### 3. 按分类获取模板

```http
GET /api/content/templates/by-category?category=技术
Authorization: Bearer {token}
```

#### 4. 搜索模板

```http
GET /api/content/templates/search?keyword=技术&page=0&size=20
Authorization: Bearer {token}
```

#### 5. 获取所有分类

```http
GET /api/content/templates/categories
Authorization: Bearer {token}
```

**响应:**
```json
["技术", "产品", "新闻", "营销"]
```

#### 6. 获取模板详情

```http
GET /api/content/templates/{templateId}
Authorization: Bearer {token}
```

#### 7. 更新模板

```http
PUT /api/content/templates/{templateId}
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "更新后的模板名称",
  "isActive": false
}
```

#### 8. 删除模板

```http
DELETE /api/content/templates/{templateId}
Authorization: Bearer {token}
```

#### 9. 复制模板

```http
POST /api/content/templates/{templateId}/duplicate
Authorization: Bearer {token}
```

#### 10. 获取统计信息

```http
GET /api/content/templates/statistics
Authorization: Bearer {token}
```

**响应:**
```json
{
  "totalTemplates": 10,
  "activeTemplates": 8,
  "categories": 4
}
```

---

### 自动化任务

#### 1. 创建自动化任务

```http
POST /api/content/automation
Authorization: Bearer {token}
Content-Type: application/json

{
  "taskName": "每日技术资讯",
  "taskType": "GENERATE_ARTICLE",
  "scheduleType": "DAILY",
  "scheduleExpression": null,
  "templateId": 1,
  "config": {
    "topic": "最新技术动态",
    "autoPublish": false,
    "notifyOnComplete": true
  },
  "isActive": true
}
```

**字段说明:**
- `taskName`: 任务名称（必填）
- `taskType`: 任务类型 - GENERATE_ARTICLE（生成文章）, PUBLISH_ARTICLE（发布文章）
- `scheduleType`: 调度类型 - ONCE（一次）, HOURLY（每小时）, DAILY（每天）, WEEKLY（每周）, MONTHLY（每月）, CUSTOM（自定义）
- `scheduleExpression`: 调度表达式（CUSTOM类型时使用，如 "2h", "30m", "7d"）
- `templateId`: 关联模板ID
- `config`: 任务配置JSON对象
- `isActive`: 是否启用

**响应:**
```json
{
  "id": 1,
  "userId": 100,
  "taskName": "每日技术资讯",
  "taskType": "GENERATE_ARTICLE",
  "scheduleType": "DAILY",
  "scheduleExpression": null,
  "templateId": 1,
  "config": {
    "topic": "最新技术动态",
    "autoPublish": false,
    "notifyOnComplete": true
  },
  "status": "PENDING",
  "lastRunAt": null,
  "nextRunAt": "2026-09-14T10:00:00",
  "runCount": 0,
  "successCount": 0,
  "failCount": 0,
  "lastError": null,
  "isActive": true,
  "createdAt": "2026-09-13T10:00:00",
  "updatedAt": "2026-09-13T10:00:00"
}
```

#### 2. 获取任务列表

```http
GET /api/content/automation?onlyActive=true&page=0&size=20
Authorization: Bearer {token}
```

#### 3. 搜索任务

```http
GET /api/content/automation/search?keyword=技术&page=0&size=20
Authorization: Bearer {token}
```

#### 4. 获取任务详情

```http
GET /api/content/automation/{taskId}
Authorization: Bearer {token}
```

#### 5. 更新任务

```http
PUT /api/content/automation/{taskId}
Authorization: Bearer {token}
Content-Type: application/json

{
  "scheduleType": "WEEKLY",
  "isActive": true
}
```

#### 6. 删除任务

```http
DELETE /api/content/automation/{taskId}
Authorization: Bearer {token}
```

#### 7. 启用任务

```http
POST /api/content/automation/{taskId}/enable
Authorization: Bearer {token}
```

#### 8. 禁用任务

```http
POST /api/content/automation/{taskId}/disable
Authorization: Bearer {token}
```

#### 9. 手动执行任务

```http
POST /api/content/automation/{taskId}/execute
Authorization: Bearer {token}
```

**响应:**
```json
{
  "success": true,
  "message": "任务执行成功",
  "task": {
    "id": 1,
    "status": "SUCCESS",
    "lastRunAt": "2026-09-13T10:00:00",
    "runCount": 1,
    "successCount": 1
  }
}
```

#### 10. 获取统计信息

```http
GET /api/content/automation/statistics
Authorization: Bearer {token}
```

**响应:**
```json
{
  "totalTasks": 10,
  "activeTasks": 8,
  "pendingTasks": 5,
  "runningTasks": 1
}
```

---

## 错误响应

所有API在出错时返回统一格式：

```json
{
  "timestamp": "2026-09-13T10:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "参数验证失败",
  "path": "/api/kb/documents"
}
```

常见HTTP状态码：
- `200`: 成功
- `201`: 创建成功
- `204`: 删除成功（无内容）
- `400`: 请求参数错误
- `401`: 未认证
- `403`: 无权限
- `404`: 资源不存在
- `500`: 服务器内部错误

---

## 认证

所有需要认证的API都需要在请求头中包含JWT token：

```http
Authorization: Bearer {your-jwt-token}
```

获取token请参考用户认证API文档。

---

## 分页

支持分页的API使用统一的查询参数：

- `page`: 页码（从0开始）
- `size`: 每页数量（默认20，最大100）

分页响应格式：

```json
{
  "content": [...],
  "totalElements": 100,
  "totalPages": 5,
  "size": 20,
  "number": 0,
  "first": true,
  "last": false
}
```

---

## 更新日志

### v1.0 (2026-09-13)
- ✅ 知识库空间和文档管理
- ✅ 文档层级结构支持
- ✅ 文档分享功能
- ✅ Markdown自动转HTML
- ✅ 内容模板系统
- ✅ 内容自动化任务
- ✅ 定时任务调度

---

## 技术支持

如有问题，请联系：
- Email: support@example.com
- 文档: https://docs.example.com
