# Website Backend

基于 Spring Boot 2.7 的后端服务。当前它不只是“导航站后端”，而是一个带用户体系的个人工作台 API，包含：

- JWT 登录、注册、改密、当前用户信息
- 用户隔离的导航分类与链接管理
- 图片生成与编辑（自定义尺寸、并发多图、OpenAI 兼容响应）、历史记录与公开分享
- AI 对话、SSE 流式回复、TTS、消息音频回放与重生成
- 知识库空间、文档树、标签、版本快照、公开分享
- QQ / 网易云 / 酷我音乐聚合查询、TuneFree 播放代理、播放历史、收藏、单曲公开分享、外部歌单导入

前端可以独立部署，后端默认监听 `http://localhost:8080`。

## 技术栈

- Java 8
- Spring Boot 2.7.18
- Spring Web
- Spring Data JPA + Hibernate
- Spring Security + JWT
- MySQL 8
- OkHttp
- Lombok
- H2（测试）

## 功能概览

### 1. 用户与权限

- `POST /api/auth/register` 普通用户自助注册
- `POST /api/auth/login` 登录并返回 JWT
- `GET /api/user/me` 返回当前用户信息和 `canManageSystemConfig`
- `POST /api/user/change-password` 修改当前用户密码

权限模型以代码为准：

- `/api/auth/**` 公开
- `/api/user/**` 需要登录
- `/api/admin/configs/**` 仅 `ADMIN`
- `/api/admin/categories/**`、`/api/admin/links/**`、`/api/admin/image/**` 是兼容旧前端的别名，只要求已登录，不是管理员专属
- `/api/admin/kb/**` 仍受 `/api/admin/**` 规则约束，只有管理员可以走该别名

### 2. 导航数据

- 分类和链接都按 `user_id` 隔离
- 匿名访问 `/api/public/categories`、`/api/public/links`、`/api/public/nav` 时，返回首个管理员账号的数据
- 已登录用户访问这些公开接口时，返回当前用户自己的数据
- 旧数据里如果 `user_id` 为空，启动时会自动迁移到默认管理员账号

### 3. 图片生成与编辑

- 支持通过 `sys_config` 在线切换上游图片接口
- 支持两类协议：
  - `image.api.baseUrl` 包含 `/images/` 时，按 OpenAI Images 风格请求（支持 `size`、`n` 参数）
  - 其他路径按 chat-completions 风格请求，并从文本 / Markdown / SSE 中提取图片地址
- 支持 OpenAI 兼容图片编辑接口：`POST /api/user/image/edit` 接收 `multipart/form-data`，后台转发到上游 `POST /v1/images/edits`
- 编辑接口默认从 `image.api.baseUrl` 推导 `/images/edits`，也可通过 `image.edit.api.baseUrl` 单独指定上游地址
- 支持设置图片尺寸（如 `1024x1024`、`1792x1024`、`1024x1792` 等，取决于模型）
- 支持并发多图生成（`n=2~10`）：`/images/generations` 端点直接传 `n` 给上游，chat-completions 端点使用线程池并发调用
- 每人每天最多生成 100 张图片，超出返回 429
- 每次成功生成或编辑后会尝试写入当前用户的历史记录（多图时每张单独记录，包含 `size` 信息）；上游返回 HTTP URL 时默认直接写入 DB，避免二次下载和 go-file 上传；上游返回 base64 时仍会通过 go-file 上传（优先）或本地文件系统（回退）落成可访问 URL
- 生图和编辑接口都是异步：POST 提交任务立即返回，后台处理，GET 轮询状态
- 如果图片已生成 / 编辑但历史入库失败，只会记录 `WARN` 日志，不影响任务状态
- 支持图片公开分享：用户可开关自己图片的公开状态，`GET /api/public/image/shared` 汇总所有人公开的图片

### 4. AI 对话

- 会话与消息都按用户隔离
- 支持同步回复和 SSE 流式回复
- 支持独立 TTS
- 支持把助手回复重新生成音频
- `inputAudioData` 支持两种输入：
  - 公网可访问的音频 URL
  - `data:audio/...;base64,...` 形式的数据
- 可用模型和默认模型来自 `sys_config`

### 5. 知识库

- 空间、文档、标签均按用户隔离
- 文档支持树形层级、状态、版本快照、标签绑定、跨空间移动
- 每次更新文档前都会生成版本快照
- 支持基于 token 的公开分享，并可设置过期时间
- 打开公开分享时，`GET /api/public/kb/share/{token}` 会同时返回当前文档内容和该用户全部仍然公开的文档列表，便于前端直接渲染左侧文档导航与右侧全屏正文

### 6. 音乐聚合

- 公开 BFF 接口，无需登录
- 支持 `qq`、`netease`、`kuwo`
- 支持搜索、播放、歌词、榜单、歌单、新歌速递
- `/play` 的解析优先级由 `music.play.resolverOrder` 控制；默认先走 TuneFree 主解析，再走 QQ 文本兜底，最后跨平台回退
- TuneFree 主解析会先尝试所请求音质，再按更低音质降级；跨平台回退顺序由 `music.play.crossSourceOrder` 控制
- TuneFree 凭据保存在 `sys_config`，首次未配置时 `/play` 会返回业务错误
- 已登录用户请求 `/api/v1/music/play` 时会自动刷新个人播放历史；每个用户最多保留最近 100 首不同歌曲
- 支持用户维度的“我喜欢的音乐”列表，按 `user + source + songId` 去重，不设数量上限；收藏状态支持批量查询（一次请求检查多个 songId）
- 支持用户维度的单曲分享，按 `user + source + songId` 去重，可轮换 token、设置过期时间
- 支持从 QQ 音乐 / 网易云音乐分享链接直接导入外部歌单，后端解析链接（含短链重定向）、拉取全部歌曲并按用户保存，用户可浏览、重命名或删除已导入的歌单
- 打开 `GET /api/public/music/share/{token}` 时会返回歌曲快照，并实时解析当前可用的 `playInfo`

## 运行要求

- JDK 8+
- Maven 3.6+
- MySQL 8+
- 能访问外部 AI / 图片 / 音乐上游服务的网络环境

## 快速启动

1. 检查并修改 [`src/main/resources/application.yml`](src/main/resources/application.yml)

   仓库当前带有开发期配置；本地或生产环境至少应覆盖：

   - `spring.datasource.url`
   - `spring.datasource.username`
   - `spring.datasource.password`
   - `app.jwt.secret`，也可以通过环境变量 `JWT_SECRET` 覆盖
   - `app.cors.allowed-origins`

2. 如需手动建库，执行：

   ```sql
   CREATE DATABASE IF NOT EXISTS website
     DEFAULT CHARACTER SET utf8mb4
     DEFAULT COLLATE utf8mb4_unicode_ci;
   ```

3. 启动项目：

   ```bash
   mvn spring-boot:run
   ```

4. 首次启动行为：

   - 自动创建默认管理员账号，用户名和密码来自 `app.admin.*`
   - 默认值是 `admin / admin123`
   - 自动补齐一批 `sys_config` 键
   - 自动迁移旧的 `user_id IS NULL` 导航数据到管理员账号

5. 首次登录后立即修改默认管理员密码：

   ```bash
   curl -X POST http://localhost:8080/api/user/change-password \
     -H "Authorization: Bearer <token>" \
     -H "Content-Type: application/json" \
     -d '{"oldPassword":"admin123","newPassword":"new-strong-password"}'
   ```

6. 运行测试：

   ```bash
   mvn test
   ```

## 配置说明

### `application.yml`

主要静态配置项：

- `spring.datasource.*`：MySQL 连接
- `spring.jpa.hibernate.ddl-auto=update`：按实体自动补齐表结构
- `spring.servlet.multipart.max-file-size` / `spring.servlet.multipart.max-request-size`：上传文件限制，默认分别为 `25MB` / `50MB`
- `app.jwt.secret`：JWT 密钥，生产环境必须替换
- `app.jwt.expire-minutes`：Token 有效期，默认 720 分钟
- `app.cors.allowed-origins`：允许的前端来源，支持 `https://*.vercel.app`
- `app.admin.default-username` / `app.admin.default-password`：默认管理员初始化信息

### `sys_config`

这些键由 `DataInitializer` 在缺失时自动补齐，可通过 `GET/POST/PUT/DELETE /api/admin/configs` 在线维护。

| Key | 说明 |
| --- | --- |
| `image.api.baseUrl` | 图片生成上游地址 |
| `image.api.key` | 图片生成上游 Bearer Key |
| `image.api.model` | 图片生成模型名 |
| `image.edit.api.baseUrl` | 可选，图片编辑上游地址；留空时从 `image.api.baseUrl` 推导 `/images/edits` |
| `image.upload.dir` | 生成图片本地存储目录，默认 `uploads/images`（go-file 不可用时回退） |
| `image.gofile.url` | go-file 服务地址，如 `https://file.example.com`。配置后优先上传到 go-file |
| `image.gofile.token` | go-file API Token，可为空（取决于 go-file 上传权限设置） |
| `image.persist.remote-url-mode` | 上游返回 HTTP 图片 URL 时的历史入库策略：`direct` 直接保存 URL（默认），`proxy` 下载后再 go-file/本地保存 |
| `ai.chat.baseUrl` | AI 对话上游基础地址，代码会自动拼接 `/chat/completions` |
| `ai.chat.apiKey` | AI 对话上游 Bearer Key，可留空 |
| `ai.chat.defaultModel` | 默认文本对话模型 |
| `ai.chat.defaultAudioModel` | 默认语音输出模型 |
| `ai.chat.models` | 允许前端选择的模型列表，逗号分隔 |
| `ai.chat.voices` | 可选，语音列表，格式 `voiceId|Label,...` |
| `music.tunefree.account` | TuneFree 账号 |
| `music.tunefree.password` | TuneFree 密码 |
| `music.tunefree.udid` | TuneFree 设备标识 |
| `music.tunefree.token` | 运行时写回的 TuneFree Token |
| `music.tunefree.token_updated_at` | Token 最近刷新时间 |
| `music.tunefree.token_status` | 最近刷新状态 |
| `music.play.resolverOrder` | 播放链接解析顺序，逗号分隔；可选值：`primary`（TuneFree 主解析）、`qq_text`（QQ 文本兜底）、`cross_source`（跨平台兜底）；默认 `primary,qq_text,cross_source` |
| `music.play.crossSourceOrder` | 跨平台兜底搜索顺序，逗号分隔；可选值：`qq`、`netease`、`kuwo`；默认 `netease,qq,kuwo` |

说明：

- `music.tunefree.token*` 这几个键由服务在运行时回写，不建议手动维护
- `ai.chat.voices` 没有显式配置时，会回退到内置默认值
- `music.play.resolverOrder` 和 `music.play.crossSourceOrder` 配错时会忽略无效项；如果没有任何有效项，后端回退到默认顺序
- 图片和 AI 能力都依赖 `sys_config`，无需改代码即可切换上游

## 数据库与 SQL 脚本

这个仓库当前没有接入 Flyway 或 Liquibase。

实际行为如下：

- 主要依赖 `spring.jpa.hibernate.ddl-auto=update` 自动建表 / 补字段
- [`src/main/resources/db/init.sql`](src/main/resources/db/init.sql) 适合新库初始化或人工核对表结构
- [`src/main/resources/db/migrations`](src/main/resources/db/migrations) 下的 SQL 是手动迁移脚本，不会自动执行

主要表：

- `user`
- `category`
- `nav_link`
- `sys_config`
- `generated_image`（含 `size`、`is_shared` 列）
- `image_generation_task`
- `ai_conversation`
- `ai_chat_message`
- `kb_space`
- `kb_doc`
- `kb_tag`
- `kb_doc_tag`
- `kb_doc_version`
- `kb_doc_share`
- `music_play_history`
- `music_favorite`
- `music_share`
- `user_playlist`
- `user_playlist_item`

## 通用响应格式

除二进制音频响应外，接口统一返回：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

约定：

- `code = 0` 表示成功
- 业务错误通常返回 `code != 0`
- 认证失败返回 HTTP `401`
- 权限不足返回 HTTP `403`
- 音乐接口的 HTTP 状态码与业务码会同时体现错误类型

## 接口总览

### 认证与用户

| Method | Path | 说明 |
| --- | --- | --- |
| POST | `/api/auth/register` | 注册普通用户 |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/user/me` | 当前用户信息 |
| POST | `/api/user/change-password` | 修改密码 |
| POST | `/api/admin/change-password` | 兼容旧路径 |

登录示例：

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

### 导航分类与链接

公开读取：

| Method | Path |
| --- | --- |
| GET | `/api/public/categories` |
| GET | `/api/public/links` |
| GET | `/api/public/nav` |

登录后读写：

| Method | Path |
| --- | --- |
| GET | `/api/user/categories` |
| GET | `/api/user/categories/{id}` |
| POST | `/api/user/categories` |
| PUT | `/api/user/categories/{id}` |
| DELETE | `/api/user/categories/{id}` |
| GET | `/api/user/links` |
| GET | `/api/user/links/{id}` |
| POST | `/api/user/links` |
| PUT | `/api/user/links/{id}` |
| DELETE | `/api/user/links/{id}` |

兼容旧路径：

- `/api/admin/categories/**`
- `/api/admin/links/**`

### 图片生成与编辑

| Method | Path | 说明 |
| --- | --- | --- |
| POST | `/api/user/image/generate` | 提交异步生图任务，立即返回任务 ID 和状态 |
| POST | `/api/user/image/edit` | 提交异步图片编辑任务，`multipart/form-data` 上传原图和可选蒙版 |
| GET | `/api/user/image/generate/{taskId}` | 查询任务状态，完成时返回生成结果 |
| GET | `/api/user/image/history?page=0&size=20` | 当前用户图片历史 |
| PATCH | `/api/user/image/history/{id}/share?shared=true` | 开启/关闭图片公开分享 |
| DELETE | `/api/user/image/history/{id}` | 删除历史记录 |

兼容旧路径：

- `/api/admin/image/**`

生图请求参数（JSON）：

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `prompt` | string | 是 | — | 图片描述，最长 2000 字符 |
| `size` | string | 否 | 上游默认 | 图片尺寸，格式 `WIDTHxHEIGHT`，如 `1024x1024`、`1792x1024` |
| `n` | integer | 否 | 1 | 生成数量，1~10。chat-completions 路径并发请求，images 路径直接传给上游 |

编辑请求参数（`multipart/form-data`）：

| 字段 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `image` | file | 是 | — | 待编辑图片，会作为上游 `/v1/images/edits` 的 `image` 字段转发 |
| `prompt` | string | 是 | — | 编辑指令，最长 2000 字符 |
| `mask` | file | 否 | — | 蒙版图片，会作为上游 `mask` 字段转发 |
| `size` | string | 否 | 上游默认 | 图片尺寸，格式 `WIDTHxHEIGHT` |
| `n` | integer | 否 | 1 | 返回图片数量，1~10 |

默认上传限制为单文件 `25MB`、单请求 `50MB`。如果线上前面还有 Nginx、网关或对象存储代理，也需要同步调大对应的请求体限制。

异步流程：`POST /generate` 或 `POST /edit` 立即返回任务对象（`PENDING`），后台线程执行生图 / 编辑，客户端轮询 `GET /generate/{taskId}` 直到 `status=COMPLETED` 或 `FAILED`。

POST 响应（提交任务，status 为 PENDING）：

```json
{
  "code": 0,
  "data": {
    "id": 42,
    "prompt": "a beautiful sunset",
    "size": "1024x1024",
    "n": 1,
    "model": "gpt-image-2",
    "status": "PENDING",
    "errorMessage": null,
    "result": null,
    "createdAt": "2026-05-17 10:00:00",
    "updatedAt": "2026-05-17 10:00:00",
    "completedAt": null
  }
}
```

GET 响应（任务完成时）：

```json
{
  "code": 0,
  "data": {
    "id": 42,
    "prompt": "a beautiful sunset",
    "status": "COMPLETED",
    "errorMessage": null,
    "result": {
      "created": 1715870400,
      "model": "gpt-image-2",
      "data": [
        {"url": "https://file.example.com/image/uuid.png", "b64Json": null, "revisedPrompt": null}
      ],
      "usage": {"total_tokens": 1024, "input_tokens": 100, "output_tokens": 924}
    },
    "completedAt": "2026-05-17 10:00:25"
  }
}
```

示例：

```bash
# 提交异步生图
curl -X POST http://localhost:8080/api/user/image/generate \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a futuristic city skyline","size":"1792x1024","n":4}'

# 提交异步图片编辑
curl -X POST http://localhost:8080/api/user/image/edit \
  -H "Authorization: Bearer <token>" \
  -F "image=@source.png" \
  -F "mask=@mask.png" \
  -F "prompt=remove the background and add soft studio lighting" \
  -F "size=1024x1024" \
  -F "n=1"

# 轮询任务状态（返回的 id 是 42）
curl http://localhost:8080/api/user/image/generate/42 \
  -H "Authorization: Bearer <token>"
```

公开图片分享：

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/public/image/shared?page=0&size=20` | 浏览所有用户公开分享的图片列表（无需登录） |
| GET | `/api/v1/image/file/{filename}` | 获取本地存储的图片文件（无需登录，7 天浏览器缓存） |

用户可通过 `PATCH /api/user/image/history/{id}/share?shared=true|false` 控制自己某张图是否对外可见。

### AI 对话与语音

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/user/ai/models` | 可用模型 |
| GET | `/api/user/ai/voices` | 可用音色 |
| POST | `/api/user/ai/tts` | 独立文本转语音，返回二进制音频 |
| POST | `/api/user/ai/conversations` | 新建会话 |
| GET | `/api/user/ai/conversations` | 会话列表 |
| GET | `/api/user/ai/conversations/{id}` | 会话详情 |
| GET | `/api/user/ai/conversations/{id}/messages` | 消息列表 |
| POST | `/api/user/ai/conversations/{id}/messages` | 同步发送消息 |
| POST | `/api/user/ai/conversations/{id}/messages?stream=true` | SSE 流式发送消息 |
| GET | `/api/user/ai/messages/{id}/audio` | 获取消息音频 |
| POST | `/api/user/ai/messages/{id}/audio` | 重新生成消息音频 |

语音合成调用 OpenAI 兼容 `POST /v1/audio/speech`：请求体使用 `model`、`input`、`voice`、`response_format`，上游返回二进制音频；后端独立 TTS 接口直接返回音频，消息语音会转为 base64 存入消息记录。

`POST /api/user/ai/conversations/{id}/messages` 支持的关键字段：

- `content`：文本消息，可为空，但不能和 `inputAudioData` 同时为空
- `model`：本次请求指定模型
- `responseAudio`：是否同时生成语音
- `ttsModel` / `ttsVoice` / `ttsFormat` / `ttsPrompt`：语音参数；`ttsFormat` 会映射到上游 `response_format`，`ttsPrompt` 会拼到待合成文本前作为风格提示
- `inputAudioData`：公网 URL 或 `data:audio/...;base64,...`

SSE 事件名：

- `meta`
- `delta`
- `audio`
- `audio_error`
- `done`
- `error`

流式示例：

```bash
curl -N -X POST "http://localhost:8080/api/user/ai/conversations/1/messages?stream=true" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"content":"给我一个项目发布清单","responseAudio":false}'
```

### 知识库

空间：

| Method | Path |
| --- | --- |
| GET | `/api/user/kb/spaces` |
| GET | `/api/user/kb/spaces/{id}` |
| POST | `/api/user/kb/spaces` |
| PUT | `/api/user/kb/spaces/{id}` |
| DELETE | `/api/user/kb/spaces/{id}` |
| GET | `/api/user/kb/spaces/{id}/tree` |

标签：

| Method | Path |
| --- | --- |
| GET | `/api/user/kb/tags` |
| POST | `/api/user/kb/tags` |
| PUT | `/api/user/kb/tags/{id}` |
| DELETE | `/api/user/kb/tags/{id}` |

文档：

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/user/kb/docs` | 搜索文档，支持 `spaceId`、`parentId`、`keyword`、`tagId`、分页 |
| POST | `/api/user/kb/docs` | 创建文档 |
| GET | `/api/user/kb/docs/{id}` | 获取文档 |
| PUT | `/api/user/kb/docs/{id}` | 更新文档并生成版本 |
| DELETE | `/api/user/kb/docs/{id}` | 删除文档 |
| POST | `/api/user/kb/docs/{id}/move` | 移动文档 |
| PUT | `/api/user/kb/docs/{id}/tags` | 替换标签 |
| GET | `/api/user/kb/docs/{id}/versions` | 版本列表 |
| GET | `/api/user/kb/docs/{id}/versions/{versionId}` | 版本详情 |
| POST | `/api/user/kb/docs/{id}/versions/{versionId}/restore` | 恢复版本 |
| GET | `/api/user/kb/docs/{id}/share` | 查询分享状态 |
| POST | `/api/user/kb/docs/{id}/share` | 创建或更新分享 |
| DELETE | `/api/user/kb/docs/{id}/share` | 关闭分享 |

公开分享：

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/public/kb/share/{token}` | 获取当前分享文档详情，并返回该用户全部仍然可访问的公开文档列表 |

返回说明：
- 顶层 `data` 仍然是当前打开的文档详情，右侧内容区直接使用 `title`、`summary`、`contentJson`、`contentHtml`
- `data.documents` 为当前分享人所有仍然有效的公开文档，包含 `id`、`token`、`title`、`summary`、`updatedAt`
- 当前打开的文档会排在 `data.documents` 第一项，前端左侧列表可直接高亮

示例：
```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 12,
    "token": "f0d6f0a87c3b4fca9a84b762b4d2d0b1",
    "title": "项目说明",
    "summary": "公开给访客查看的说明文档",
    "contentJson": "{}",
    "contentHtml": "<p>项目说明</p>",
    "updatedAt": "2026-05-08T15:30:00",
    "documents": [
      {
        "id": 12,
        "token": "f0d6f0a87c3b4fca9a84b762b4d2d0b1",
        "title": "项目说明",
        "summary": "公开给访客查看的说明文档",
        "updatedAt": "2026-05-08T15:30:00"
      },
      {
        "id": 18,
        "token": "3b55c6dbe4354623bb84d6950e8742c9",
        "title": "部署文档",
        "summary": "公开部署步骤",
        "updatedAt": "2026-05-07T10:00:00"
      }
    ]
  }
}
```

知识库约束：

- `contentJson` 如果传入，必须是合法 JSON
- `status` 只允许 `draft` 或 `published`
- 不允许把文档移动到自己的子孙节点下

### 音乐聚合

| Method | Path |
| --- | --- |
| GET | `/api/v1/music/search?source=&keyword=&page=&pageSize=` |
| GET | `/api/v1/music/play?source=&id=&quality=` |
| GET | `/api/v1/music/lyric?source=&id=` |
| GET | `/api/v1/music/toplist?source=` |
| GET | `/api/v1/music/toplist/detail?source=&id=&page=&pageSize=` |
| GET | `/api/v1/music/playlist?source=&category=&order=&page=&pageSize=` |
| GET | `/api/v1/music/playlist/detail?source=&id=&page=&pageSize=` |
| GET | `/api/v1/music/new?source=&page=&pageSize=` |

用户音乐库：

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/user/music/history?page=0&size=20` | 当前用户播放历史，按最近播放时间倒序，最多保留 100 首不同歌曲 |
| DELETE | `/api/user/music/history/{id}` | 删除一条播放历史 |
| GET | `/api/user/music/favorites?page=0&size=20` | 当前用户“我喜欢的音乐”列表 |
| POST | `/api/user/music/favorites` | 新增或更新一首喜欢的音乐 |
| DELETE | `/api/user/music/favorites?source=&songId=` | 取消喜欢 |
| POST | `/api/user/music/favorites/status` | 批量查询喜欢状态，body `{"source":"netease","songIds":["id1","id2"]}`，返回 `[{source, songId, liked, favoriteId}]` |
| GET | `/api/user/music/shares/status?source=&songId=` | 查询某首歌的分享状态 |
| POST | `/api/user/music/shares` | 创建或更新单曲分享，可设置过期时间，支持轮换 token |
| DELETE | `/api/user/music/shares?source=&songId=` | 关闭单曲分享 |
| POST | `/api/user/music/playlists/import` | 从 QQ/网易云分享链接导入外部歌单 |
| GET | `/api/user/music/playlists?page=0&size=20` | 当前用户已导入的歌单列表 |
| GET | `/api/user/music/playlists/{id}?page=0&size=30` | 查看某个导入歌单的歌曲列表 |
| PATCH | `/api/user/music/playlists/{id}` | 重命名歌单 |
| DELETE | `/api/user/music/playlists/{id}` | 删除已导入的歌单（连带歌曲） |
| DELETE | `/api/user/music/playlists/{id}/items/{itemId}` | 从歌单中移除某首歌 |

公开音乐分享：

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/public/music/share/{token}` | 获取分享歌曲快照，并实时解析当前可播放地址 |

参数说明：

- `source`：`qq` / `netease` / `kuwo`
- `quality`：`128k` / `320k` / `flac` / `flac24bit`
- `POST /api/user/music/favorites/status` 支持 JSON body 批量查询，`{"source":"netease","songIds":["id1","id2"]}`，返回 `List<MusicFavoriteStatusView>`
- `POST /api/user/music/shares` 请求体字段与收藏接口一致，并额外支持：
  `requestedQuality`：公开分享时优先解析的音质，默认 `flac`
  `expiresAt`：分享过期时间，可为空
  `rotateToken`：是否轮换分享 token，默认 `false`
- 对 `/api/v1/music/play` 携带 `Authorization: Bearer <token>` 时，接口仍然公开可访问，但会额外写入该用户的播放历史
- `POST /api/user/music/playlists/import` 请求体 `{ "url": "分享链接" }`，支持：
  - QQ 音乐桌面链接 `y.qq.com/n/ryqq/playlist/xxx`
  - QQ 音乐移动端套歌链接 `i.y.qq.com/n2/m/share/details/taoge.html?id=xxx`
  - QQ 音乐歌单页 `y.qq.com/portal/playlist.html?disstid=xxx`
  - 网易云音乐桌面链接 `music.163.com/#/playlist?id=xxx`
  - 网易云音乐移动端 `music.163.com/m/playlist?id=xxx`
  - 短链 `c.y.qq.com/...` / `163cn.tv/...`（自动跟随重定向解析）
  - 同一用户重复导入同一歌单会返回 409
  - 单次最多导入 1000 首
- `GET /api/public/music/share/{token}` 返回歌曲基础信息外，还会包含：
  `playable`：当前是否成功解析到播放地址
  `playError`：解析失败时的错误信息
  `playInfo`：若解析成功，结构与 `/api/v1/music/play` 返回一致

缓存策略：

- `search`：60 秒
- `play`：1200 秒
- `lyric`：6 小时
- `toplist` / `playlist`：10 到 30 分钟

常见音乐业务码：

| Code | 含义 |
| --- | --- |
| `1001` | 无效来源 |
| `1002` | 无效音质 |
| `1003` | 未配置 TuneFree 凭据 |
| `1005` | 上游播放失败 |
| `1006` | 上游歌词失败 |
| `1007` | 歌曲不存在 |
| `1008` | 没有可播放链接 |
| `1009` | 上游超时 |
| `1011` | 上游歌单请求失败 |
| `1012` | 不支持的操作 |

## 项目结构

```text
src/main/java/com/example/website
├─ common        # 统一响应、异常处理
├─ config        # JWT、CORS、OkHttp、初始化
├─ controller    # REST API
├─ dto           # 请求 / 响应对象
├─ entity        # JPA 实体
├─ repository    # 数据访问
├─ service       # 业务逻辑
│  ├─ kb         # 知识库
│  └─ music      # 音乐聚合
└─ util          # JWT 工具

src/main/resources
├─ application.yml
└─ db
   ├─ init.sql
   └─ migrations
```

## 开发提示

- `GET /api/user/me` 里的 `canManageSystemConfig` 可直接给前端用来控制“系统配置”入口显隐
- 图片、AI、音乐三块都依赖外部上游，联调前先确认 `sys_config` 是否填完整
- `OkHttpClient` 按场景区分超时：AI 读取超时 180 秒，图片上游调用总超时 360 秒，音乐请求更短。图片编辑上游较慢时可从 [`src/main/java/com/example/website/config/OkHttpConfig.java`](src/main/java/com/example/website/config/OkHttpConfig.java) 调整
- 生产环境不要继续使用默认 JWT 密钥和默认管理员密码
