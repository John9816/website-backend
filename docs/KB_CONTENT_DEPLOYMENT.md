# 知识库和内容工厂部署指南

## 📋 部署前准备

### 1. 检查完成的功能

后端已完成：
- ✅ **知识库系统**
  - `KbDocument` 实体（支持层级结构、Markdown、分享）
  - `KbDocumentRepository` 数据访问层
  - `KbDocumentService` 业务逻辑（40+方法）
  - `KbDocumentController` API端点
  
- ✅ **内容工厂系统**
  - `ContentTemplate` 实体（模板管理）
  - `ContentAutomationTask` 实体（自动化任务）
  - `ContentTemplateService` 业务逻辑
  - `ContentAutomationTaskService` 业务逻辑（含定时任务）
  - `ContentTemplateController` API端点
  - `ContentAutomationTaskController` API端点

- ✅ **数据库迁移**
  - `0011_kb_content_enhancements.sql`（完整建表和索引）
  - 包含3个系统默认模板

- ✅ **文档**
  - `KB_CONTENT_API.md`（完整API文档）
  - 本部署指南

### 2. 文件清单

```
website-backend/
├── src/main/java/com/example/website/
│   ├── entity/
│   │   ├── KbDocument.java ✅
│   │   ├── ContentTemplate.java ✅
│   │   └── ContentAutomationTask.java ✅
│   ├── repository/
│   │   ├── KbDocumentRepository.java ✅
│   │   ├── ContentTemplateRepository.java ✅
│   │   └── ContentAutomationTaskRepository.java ✅
│   ├── service/
│   │   ├── kb/KbDocumentService.java ✅
│   │   └── content/
│   │       ├── ContentTemplateService.java ✅
│   │       └── ContentAutomationTaskService.java ✅
│   └── controller/
│       ├── KbDocumentController.java ✅
│       ├── ContentTemplateController.java ✅
│       └── ContentAutomationTaskController.java ✅
├── db/migrations/
│   └── 0011_kb_content_enhancements.sql ✅
├── docs/
│   └── KB_CONTENT_API.md ✅
└── deploy-kb-content.sh ✅
```

---

## 🚀 部署步骤（方式一：自动脚本）

### 前提条件
确保已安装 `sshpass`：
```bash
# macOS
brew install hudochenkov/sshpass/sshpass

# 或者使用手动方式（方式二）
```

### 执行部署
```bash
cd /Users/wangweijie/projects/idea/website-backend
./deploy-kb-content.sh
```

脚本会自动完成：
1. 编译后端项目
2. 备份ECS数据库
3. 上传JAR包和SQL文件
4. 执行数据库迁移
5. 重启服务
6. 验证部署

---

## 🔧 部署步骤（方式二：手动执行）

### 步骤1: 编译项目

```bash
cd /Users/wangweijie/projects/idea/website-backend
mvn clean package -DskipTests
```

**预期输出:**
```
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  XX.XXX s
```

编译后的JAR位置：
```
target/website-0.0.1-SNAPSHOT.jar
```

---

### 步骤2: 备份ECS数据库

```bash
ssh root@8.156.95.93

# 在ECS上执行
mkdir -p /backup
mysqldump -u root -p'wangweijie1314.' website > /backup/website_before_kb_$(date +%Y%m%d_%H%M%S).sql

# 确认备份成功
ls -lh /backup/website_before_kb_*.sql

exit
```

---

### 步骤3: 上传文件到ECS

```bash
# 上传JAR包
scp target/website-0.0.1-SNAPSHOT.jar root@8.156.95.93:/opt/website/backend/website.jar

# 上传数据库迁移脚本
scp db/migrations/0011_kb_content_enhancements.sql root@8.156.95.93:/tmp/
```

---

### 步骤4: 执行数据库迁移

```bash
ssh root@8.156.95.93

# 执行迁移
mysql -u root -p'wangweijie1314.' website < /tmp/0011_kb_content_enhancements.sql

# 验证表是否创建成功
mysql -u root -p'wangweijie1314.' website -e "SHOW TABLES LIKE 'kb_%'; SHOW TABLES LIKE 'content_template%';"
```

**预期输出:**
```
+------------------------+
| Tables_in_website (kb_%) |
+------------------------+
| kb_document            |
| kb_space               |
+------------------------+

+----------------------------------+
| Tables_in_website (content_template%) |
+----------------------------------+
| content_automation_task           |
| content_template                  |
+----------------------------------+
```

验证默认模板：
```bash
mysql -u root -p'wangweijie1314.' website -e "SELECT id, name, template_type FROM content_template WHERE template_type='SYSTEM';"
```

**预期输出:**
```
+----+------------------+---------------+
| id | name             | template_type |
+----+------------------+---------------+
|  1 | 技术博客模板      | SYSTEM        |
|  2 | 产品分析模板      | SYSTEM        |
|  3 | 新闻报道模板      | SYSTEM        |
+----+------------------+---------------+
```

---

### 步骤5: 重启服务

```bash
# 仍在ECS上
systemctl restart website-api

# 等待5秒
sleep 5

# 检查状态
systemctl status website-api

# 查看启动日志
journalctl -u website-api -n 50 --no-pager

exit
```

**预期日志包含:**
```
Started WebsiteApplication in XX.XXX seconds
Exposing 4 endpoint(s) beneath base path '/actuator'
```

---

### 步骤6: 验证部署

```bash
# 1. 健康检查
curl https://api.751152.xyz/actuator/health

# 预期输出: {"status":"UP"}

# 2. 检查知识库API
curl -I https://api.751152.xyz/api/kb/spaces

# 预期: HTTP/1.1 401 Unauthorized (说明端点存在，需要认证)

# 3. 检查内容模板API
curl -I https://api.751152.xyz/api/content/templates

# 预期: HTTP/1.1 401 Unauthorized

# 4. 检查文档API
curl -I https://api.751152.xyz/api/kb/documents

# 预期: HTTP/1.1 401 Unauthorized
```

---

## ✅ 功能验证

### 1. 使用Postman或curl测试

参考 `docs/KB_CONTENT_API.md` 文档进行完整测试。

### 示例：创建知识库空间

```bash
# 首先获取JWT token（假设你已有认证接口）
TOKEN="your-jwt-token"

# 创建空间
curl -X POST https://api.751152.xyz/api/kb/spaces \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "我的第一个知识库",
    "description": "测试知识库",
    "icon": "📚",
    "isPublic": false
  }'
```

**预期响应:** 包含 `id`, `name`, `createdAt` 等字段的JSON

### 示例：创建文档

```bash
curl -X POST https://api.751152.xyz/api/kb/documents \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "spaceId": 1,
    "title": "测试文档",
    "content": "# 测试\n\n这是一个测试文档",
    "status": "DRAFT"
  }'
```

### 示例：获取系统模板

```bash
curl -X GET https://api.751152.xyz/api/content/templates \
  -H "Authorization: Bearer $TOKEN"
```

**预期:** 返回包含3个系统模板的列表

---

## 📊 数据库结构

### 新增表

1. **kb_document** - 知识库文档
   - 支持Markdown内容和自动HTML转换
   - 支持层级结构（parent_id）
   - 支持公开分享（share_token）
   - 自动计算字数和阅读时间

2. **content_template** - 内容模板
   - 支持系统模板和用户自定义模板
   - 支持模板变量（{{var}}）
   - 支持AI提示词
   - 记录使用次数

3. **content_automation_task** - 自动化任务
   - 支持多种调度类型
   - 记录执行历史
   - 支持任务配置JSON

### 修改表

- **kb_space**: 新增 `config_json`, `is_default`, `document_count` 字段
- **content_article**: 新增 `template_id` 字段（关联模板）

---

## 🔍 故障排查

### 问题1: 编译失败

```bash
# 清理Maven缓存
mvn clean
rm -rf ~/.m2/repository/com/example/website

# 重新编译
mvn package -DskipTests
```

### 问题2: 服务启动失败

```bash
# 查看完整日志
ssh root@8.156.95.93
journalctl -u website-api -n 200 --no-pager

# 常见错误：
# - 数据库连接失败: 检查 /opt/website/backend/.env
# - 端口占用: lsof -i :8090
# - 内存不足: free -h
```

### 问题3: 数据库迁移失败

```bash
# 回滚到备份
mysql -u root -p'wangweijie1314.' website < /backup/website_before_kb_XXXXXX.sql

# 检查SQL语法
mysql -u root -p'wangweijie1314.' website < /tmp/0011_kb_content_enhancements.sql
```

### 问题4: API返回404

```bash
# 确认服务是否启动
curl https://api.751152.xyz/actuator/health

# 检查Nginx配置
ssh root@8.156.95.93
nginx -t
systemctl status nginx
```

---

## 🎯 下一步：前端集成

### 1. 前端代码位置
```
/Users/wangweijie/projects/vs/website
```

### 2. 需要创建的前端页面

**知识库模块:**
- `/kb` - 知识库空间列表
- `/kb/:spaceId` - 空间详情（文档列表）
- `/kb/:spaceId/docs/:docId` - 文档详情
- `/kb/:spaceId/docs/:docId/edit` - 文档编辑器

**内容工厂模块:**
- `/content/templates` - 模板管理
- `/content/automation` - 自动化任务管理
- `/content/factory` - 内容生成工厂

### 3. 推荐的前端技术栈
- **编辑器**: TinyMCE 或 Toast UI Editor（Markdown）
- **UI组件**: Ant Design 或 Element Plus
- **状态管理**: Pinia 或 Vuex
- **HTTP客户端**: Axios

---

## 📝 API测试清单

部署完成后，依次测试以下API：

### 知识库
- [ ] POST /api/kb/spaces - 创建空间
- [ ] GET /api/kb/spaces - 获取空间列表
- [ ] POST /api/kb/documents - 创建文档
- [ ] GET /api/kb/documents - 获取文档列表
- [ ] PUT /api/kb/documents/:id - 更新文档
- [ ] POST /api/kb/documents/:id/share - 生成分享链接
- [ ] GET /api/public/kb/share/:token - 公开访问分享文档

### 内容模板
- [ ] GET /api/content/templates - 获取模板列表（应包含3个系统模板）
- [ ] POST /api/content/templates - 创建自定义模板
- [ ] GET /api/content/templates/categories - 获取分类列表

### 自动化任务
- [ ] POST /api/content/automation - 创建任务
- [ ] GET /api/content/automation - 获取任务列表
- [ ] POST /api/content/automation/:id/execute - 手动执行任务

---

## 🔐 安全注意事项

1. **数据库备份**: 每次迁移前务必备份
2. **敏感信息**: 不要在日志中输出用户内容
3. **访问控制**: 所有API都需要JWT认证
4. **公开分享**: 只有visibility=PUBLIC的文档可通过shareToken访问
5. **定时任务**: 每分钟执行一次，注意性能影响

---

## 📞 支持

遇到问题？
1. 查看日志: `journalctl -u website-api -f`
2. 检查数据库: `mysql -u root -p website`
3. 查看API文档: `docs/KB_CONTENT_API.md`
4. 健康检查: `https://api.751152.xyz/actuator/health`

---

## 🎉 部署完成

恭喜！知识库和内容工厂系统已部署完成。

**新增功能:**
- ✅ 完整的知识库管理系统
- ✅ 文档Markdown编辑和HTML转换
- ✅ 文档分享功能
- ✅ 内容模板系统（含3个默认模板）
- ✅ 内容自动化任务
- ✅ 定时任务调度

**API端点:**
- 知识库: `/api/kb/*`
- 内容模板: `/api/content/templates`
- 自动化任务: `/api/content/automation`

现在可以开始开发前端界面了！🚀
