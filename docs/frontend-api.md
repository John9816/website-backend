# 前端接口文档

本文档面向 Web、Android 等客户端开发，接口实现以当前 Spring Boot 后端代码为准。

## 1. 接入约定

### Base URL

| 环境 | 地址 |
| --- | --- |
| 生产环境 | `https://hi.751152.xyz` |
| 本地环境 | `http://localhost:8080` |

接口路径已经包含 `/api`，拼接时不要重复添加。例如登录接口为 `https://hi.751152.xyz/api/auth/login`。

### 通用响应

除 SSE、图片、音频和安装包等文件响应外，接口统一返回：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

前端必须同时判断 HTTP 状态码和响应体 `code`：

| Code | 前端处理 |
| --- | --- |
| `0` | 请求成功 |
| `400` | 显示请求参数或业务错误 |
| `401` | 清理本地会话并跳转登录页 |
| `403` | 权限不足；音乐播放接口也用它表示会员无效 |
| `404` | 资源不存在 |
| `409` | 资源冲突，例如用户名、邮箱或歌单重复 |
| `413` | 上传文件过大 |
| `429` | 请求过于频繁或达到次数限制 |
| `500` | 后端内部错误 |
| `502` / `503` / `504` | 上游服务或后端依赖暂时不可用 |

业务失败示例：

```json
{
  "code": 400,
  "message": "邮箱验证码不存在、已过期或不正确",
  "data": null
}
```

### 登录态

登录和注册成功后，将 `data.token` 保存为访问令牌，将 `data.refreshToken` 保存为刷新令牌。需要登录的请求携带：

```http
Authorization: Bearer <token>
```

访问令牌过期后调用 `POST /api/auth/refresh`。刷新成功会同时返回新的访问令牌和刷新令牌，前端必须原子替换二者。刷新失败时不要重复重试，应清理登录态。

不要缓存认证接口响应。后端已对登录、注册、刷新、找回密码和当前用户接口返回 `Cache-Control: no-store`。

### 分页

大部分后台列表使用从 `0` 开始的 `page` 和 `size`。音乐上游列表通常使用从 `1` 开始的 `page` 和 `pageSize`，以具体接口表为准。

通用分页数据结构：

```json
{
  "items": [],
  "total": 0,
  "page": 0,
  "size": 20
}
```

## 2. 认证与账号

### 2.1 发送注册验证码

`POST /api/auth/email-code`

无需登录。仅支持 QQ 邮箱和 `751152.xyz` 邮箱。

```json
{
  "email": "user@751152.xyz"
}
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "resendAfterSeconds": 60,
    "expiresInSeconds": 600
  }
}
```

前端应使用 `resendAfterSeconds` 控制重新发送按钮倒计时，使用 `expiresInSeconds` 提示验证码有效期。已注册邮箱也返回相同成功结构，但不会发送注册邮件，以减少账号探测。

### 2.2 注册

`POST /api/auth/register`

```json
{
  "username": "alice",
  "email": "alice@751152.xyz",
  "password": "secret123",
  "verificationCode": "123456"
}
```

| 字段 | 约束 |
| --- | --- |
| `username` | 3 到 50 个字符，必填且唯一 |
| `email` | QQ 邮箱或 `751152.xyz` 邮箱，必填且唯一 |
| `password` | 6 到 64 个字符 |
| `verificationCode` | 注册邮件中的 6 位数字验证码 |

注册成功后直接返回登录数据，前端可以进入已登录状态。

### 2.3 登录

`POST /api/auth/login`

用户名登录：

```json
{
  "username": "alice",
  "password": "secret123"
}
```

邮箱登录：

```json
{
  "email": "alice@751152.xyz",
  "password": "secret123"
}
```

`username` 和 `email` 必须二选一，不能同时传。`username` 字段只按用户名查询，`email` 字段只按邮箱查询。前端登录框输入内容包含 `@` 时应传 `email`，否则传 `username`。

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "<jwt>",
    "tokenType": "Bearer",
    "expiresInMinutes": 720,
    "refreshToken": "<opaque-refresh-token>",
    "username": "alice",
    "role": "USER"
  }
}
```

### 2.4 刷新令牌

`POST /api/auth/refresh`

```json
{
  "refresh_token": "<refresh-token>"
}
```

请求字段也兼容 `refreshToken` 和 `refresh`。每次刷新都会轮换刷新令牌，旧刷新令牌立即失效。成功响应与登录响应相同。

### 2.5 退出登录

`POST /api/auth/logout`

```json
{
  "refresh_token": "<refresh-token>"
}
```

退出接口是幂等的。无论后端撤销是否命中，前端都应清理本地访问令牌和刷新令牌。

### 2.6 发送找回密码验证码

`POST /api/auth/password-reset/email-code`

无需登录。

```json
{
  "email": "alice@751152.xyz"
}
```

成功响应与发送注册验证码相同。为防止通过接口枚举账号，不存在的邮箱也会返回成功，但不会实际发信。

注册验证码和找回密码验证码用途隔离，二者不能混用。

### 2.7 确认重置密码

`POST /api/auth/password-reset/confirm`

无需登录。

```json
{
  "email": "alice@751152.xyz",
  "verificationCode": "123456",
  "newPassword": "new-secret123"
}
```

`newPassword` 长度为 6 到 64 个字符。成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": null
}
```

重置成功后，该用户以前签发的 JWT 和全部刷新令牌都会失效。前端应清理所有本地会话并跳转登录页，让用户使用新密码重新登录。

推荐页面流程：

1. 输入邮箱并调用发送验证码接口。
2. 根据 `resendAfterSeconds` 启动重新发送倒计时。
3. 输入 6 位验证码和新密码，调用确认接口。
4. 成功后清理令牌并跳转登录页。
5. `400` 显示后端 `message`，`429` 提示重新获取验证码或稍后重试。

### 2.8 当前用户与资料

| Method | Path | Content-Type | 说明 |
| --- | --- | --- | --- |
| GET | `/api/user/me` | - | 当前用户资料和权限 |
| PUT | `/api/user/profile` | `application/json` | 修改用户名和邮箱 |
| POST | `/api/user/avatar` | `multipart/form-data` | 上传头像，文件字段名为 `file` |
| GET | `/api/v1/user/avatar/{filename}` | - | 公开读取头像文件 |
| POST | `/api/user/change-password` | `application/json` | 使用旧密码修改密码 |

修改资料请求：

```json
{
  "username": "alice",
  "email": "alice@751152.xyz"
}
```

修改密码请求：

```json
{
  "oldPassword": "secret123",
  "newPassword": "new-secret123"
}
```

`GET /api/user/me` 的关键响应字段：

```json
{
  "id": 1,
  "username": "alice",
  "email": "alice@751152.xyz",
  "role": "USER",
  "enabled": true,
  "canManageSystemConfig": false,
  "avatarUrl": "/api/v1/user/avatar/example.png"
}
```

## 3. 权限分类

| 路径 | 权限 |
| --- | --- |
| `/health`、`/updates/**` | 公开 |
| `/api/auth/**` | 公开 |
| `GET /api/public/**` | 公开 |
| 公开图片、头像、知识库附件、内容附件文件接口 | 公开 |
| `/api/user/**` | 已登录用户 |
| `/api/v1/music/**` | 已登录用户；其中 `/play` 还要求有效会员 |
| `/api/admin/**` | `ADMIN` 角色 |

前端页面不应仅依赖菜单显隐控制权限，仍要处理接口返回的 `401` 和 `403`。

## 4. 业务接口索引

下表用于前端快速定位路由。复杂请求字段和响应模型可结合对应页面的现有 DTO 类型联调。

### 4.1 健康检查与应用更新

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | 公开 | 后端健康检查 |
| GET | `/updates/latest.json` | 公开 | 最新安装包元数据 |
| GET | `/updates/{filename}` | 公开 | 下载更新文件 |
| GET | `/api/public/app/update` | 公开 | 查询最新应用版本 |
| GET | `/api/public/music/installer/latest` | 公开 | 查询音乐客户端安装包 |

### 4.2 导航分类与链接

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/public/categories` | 公开 | 分类列表 |
| GET | `/api/public/links` | 公开 | 链接列表 |
| GET | `/api/public/nav` | 公开 | 分类和链接聚合数据 |
| GET/POST | `/api/user/categories` | 登录 | 查询或创建分类 |
| GET/PUT/DELETE | `/api/user/categories/{id}` | 登录 | 分类详情、修改、删除 |
| GET/POST | `/api/user/links` | 登录 | 查询或创建链接 |
| GET/PUT/DELETE | `/api/user/links/{id}` | 登录 | 链接详情、修改、删除 |

### 4.3 图片生成与分享

上游使用 [WisArt OpenAI 兼容 API 文档](https://wisart.kuaileshifu.com/#/member/api-docs)。本站后端负责保存任务、异步执行和历史记录，不把 WisArt API Key 暴露给前端。

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/user/image/models` | 登录 | 从 WisArt `/v1/models` 获取当前可用图片模型 |
| POST | `/api/user/image/generate` | 有效会员 | 提交异步生图任务，JSON |
| POST | `/api/user/image/edit` | 有效会员 | 提交异步编辑任务，表单上传 |
| GET | `/api/user/image/generate/{taskId}` | 登录 | 查询任务状态 |
| GET | `/api/user/image/tasks` | 登录 | 可恢复任务列表，仅返回 `PENDING`、`PROCESSING`、`FAILED` |
| GET | `/api/user/image/history` | 登录 | 图片历史，支持 `page`、`size` |
| PATCH | `/api/user/image/history/{id}/share?shared=true` | 登录 | 开关公开分享 |
| POST | `/api/user/image/history/{id}/retry` | 登录 | 重试失败任务 |
| DELETE | `/api/user/image/history/{id}` | 登录 | 删除图片历史 |
| GET | `/api/public/image/shared` | 公开 | 公开图片分页列表 |
| GET | `/api/v1/image/file/{filename}` | 公开 | 读取本地图片文件 |

`/api/admin/image/**` 是兼容旧前端的别名，与 `/api/user/image/**` 使用相同的登录权限和用户数据隔离规则；新客户端建议使用 `/api/user/image/**`。

普通用户生图和图片编辑在创建任务前会校验当前账号是否有有效会员。有效会员每天只能提交 1 次生图或编辑任务，两者共用额度；额度按任务提交计算，即使上游失败也会消耗当天额度。管理员不受会员校验和每日额度限制。普通用户当天已使用额度时返回 HTTP `429`，不会创建新任务；没有会员或会员已过期时返回 HTTP `403`，不会创建任务。模型查询、任务查询、任务列表、图片历史、分享开关和公开图片列表不要求有效会员，但仍按各自的登录/公开权限执行。

生图 JSON 字段为 `model`、`prompt`、`size`、`n`。编辑接口使用 `multipart/form-data`，字段为 `image`、`prompt`、可选 `model`、`mask`、`size`、`n`。`model` 不传时使用后端 `sys_config.image.api.model`。普通会员提交前需要有效会员且当天未使用额度，管理员不受这两项限制；提交后轮询任务，直到 `status` 为 `COMPLETED` 或 `FAILED`。`n=1~3` 表示本次任务的图片数量，不会增加普通会员每天一次的额度。

图片数据来源于上游返回的 `url`。远程 URL 会直接返回并保存；Base64 图片会按后端配置使用 GoFile 或本地图片文件存储。后端不向前端暴露 WisArt API Key。


模型接口返回：

```json
{
  "code": 0,
  "data": [
    {"id": "gpt-image-2", "object": "model", "created": 0, "ownedBy": "内置令牌"}
  ]
}
```

#### 任务状态与任务记录

任务状态包括：

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 已创建，等待后台执行 |
| `PROCESSING` | 正在调用图片上游 |
| `COMPLETED` | 已完成，`result` 中包含图片结果 |
| `FAILED` | 生成或编辑失败，错误信息在 `errorMessage` |
| `CANCELLED` | 用户取消或删除了仍在执行的任务 |

查询单个任务：

```http
GET /api/user/image/generate/{taskId}
Authorization: Bearer <token>
```

任务列表：

```http
GET /api/user/image/tasks?page=0&size=20
Authorization: Bearer <token>
```

返回分页结构：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": 42,
        "prompt": "a futuristic city skyline",
        "size": "1792x1024",
        "n": 1,
        "model": "gpt-image-2",
        "status": "FAILED",
        "errorMessage": "Upstream returned no image URL",
        "result": null,
        "createdAt": "2026-05-17 10:00:00",
        "updatedAt": "2026-05-17 10:01:00",
        "completedAt": "2026-05-17 10:01:00"
      }
    ],
    "total": 1,
    "page": 0,
    "size": 20
  }
}
```

注意：`/api/user/image/tasks` 不是全部历史任务查询，只用于恢复未完成或失败任务。已完成图片请查询图片历史；当前没有单独的“全部任务历史”接口。

失败任务可以重试：

```http
POST /api/user/image/history/{taskId}/retry
Authorization: Bearer <token>
```

只有 `FAILED` 任务可以重试。普通用户重试时要求当前账号拥有有效会员和当天剩余额度，管理员不受这两项限制。重试成功后会重新执行任务并写入图片历史；普通用户失败任务的首次提交已经消耗当天额度时，重试返回 HTTP `429`，不会改变任务状态；会员过期时返回 HTTP `403`，不会改变任务状态。

#### 用户生图历史

`GET /api/user/image/history?page=0&size=20` 查询当前登录用户已保存的图片记录，按最新记录倒序返回。分页参数：`page` 最小为 `0`，`size` 范围为 `1~100`，默认 `size=20`。

返回结构：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      {
        "id": 1001,
        "type": "image",
        "prompt": "a futuristic city skyline",
        "imageUrl": "https://upstream.example/image.png",
        "model": "gpt-image-2",
        "size": "1792x1024",
        "isShared": false,
        "createdAt": "2026-05-17 10:00:25"
      }
    ],
    "total": 1,
    "page": 0,
    "size": 20
  }
}
```

历史记录操作：

```http
# 开启或关闭公开分享
PATCH /api/user/image/history/{id}/share?shared=true
Authorization: Bearer <token>

# 删除当前用户的一条图片历史
DELETE /api/user/image/history/{id}
Authorization: Bearer <token>
```

分享开关只能操作当前用户自己的图片。删除历史记录不会删除上游图片或已保存的本地文件。

### 4.4 AI 对话与语音

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/user/ai/models` | 登录 | 模型列表 |
| GET | `/api/user/ai/voices` | 登录 | 音色列表 |
| POST | `/api/user/ai/tts` | 登录 | 文本转音频，返回二进制 |
| GET/POST | `/api/user/ai/conversations` | 登录 | 会话列表或创建会话 |
| GET/DELETE | `/api/user/ai/conversations/{id}` | 登录 | 会话详情或删除会话 |
| GET | `/api/user/ai/conversations/{id}/messages` | 登录 | 消息列表 |
| POST | `/api/user/ai/conversations/{id}/messages` | 登录 | 同步发送消息 |
| POST | `/api/user/ai/conversations/{id}/messages?stream=true` | 登录 | SSE 流式发送消息 |
| GET/POST | `/api/user/ai/messages/{id}/audio` | 登录 | 获取或重新生成消息音频 |

SSE 事件包括 `meta`、`delta`、`audio`、`audio_error`、`done` 和 `error`。SSE 和二进制音频响应不使用 `ApiResponse` 包装。

### 4.5 知识库

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| GET/POST | `/api/user/kb/spaces` | 登录 | 空间列表或创建空间 |
| GET/PUT/DELETE | `/api/user/kb/spaces/{id}` | 登录 | 空间详情、修改、删除 |
| GET | `/api/user/kb/spaces/{id}/tree` | 登录 | 文档树 |
| GET/POST | `/api/user/kb/tags` | 登录 | 标签列表或创建标签 |
| PUT/DELETE | `/api/user/kb/tags/{id}` | 登录 | 修改或删除标签 |
| GET/POST | `/api/user/kb/docs` | 登录 | 搜索或创建文档 |
| GET/PUT/DELETE | `/api/user/kb/docs/{id}` | 登录 | 文档详情、修改、删除 |
| POST | `/api/user/kb/docs/{id}/move` | 登录 | 移动文档 |
| PUT | `/api/user/kb/docs/{id}/tags` | 登录 | 替换标签 |
| GET | `/api/user/kb/docs/{id}/versions` | 登录 | 版本列表 |
| GET | `/api/user/kb/docs/{id}/versions/{versionId}` | 登录 | 版本详情 |
| POST | `/api/user/kb/docs/{id}/versions/{versionId}/restore` | 登录 | 恢复版本 |
| GET/POST/DELETE | `/api/user/kb/docs/{id}/share` | 登录 | 查询、开启或关闭分享 |
| POST | `/api/user/kb/assets` | 登录 | 上传知识库附件 |
| GET | `/api/v1/kb/assets/{filename}` | 公开 | 读取知识库附件 |
| GET | `/api/public/kb/share/{token}` | 公开 | 读取公开分享文档 |

文档 `contentJson` 必须是合法 JSON，`status` 只允许 `draft` 或 `published`。

### 4.6 音乐聚合

以下接口均要求登录。`source` 可选 `qq`、`netease`、`kuwo`；其中 `/api/v1/music/play` 还要求有效会员。未登录返回 `401`，无有效会员返回 `403`。

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/v1/music/search?source=&keyword=&type=&page=1&pageSize=10` | 搜索 |
| GET | `/api/v1/music/play?source=&id=&quality=` | 获取播放地址，需要有效会员 |
| GET | `/api/v1/music/lyric?source=&id=` | 歌词 |
| GET | `/api/v1/music/toplist?source=` | 榜单列表 |
| GET | `/api/v1/music/toplist/detail?source=&id=&page=1&pageSize=30` | 榜单详情 |
| GET | `/api/v1/music/playlist?source=&category=&order=&page=1&pageSize=20` | 歌单列表 |
| GET | `/api/v1/music/playlist/recommend?source=&limit=20` | 首页推荐歌单，支持三种音乐源 |
| GET | `/api/v1/music/playlist/detail?source=&id=&page=1&pageSize=30` | 歌单详情 |
| GET | `/api/v1/music/album/detail?source=&id=&page=1&pageSize=30` | 专辑详情和分页歌曲 |
| GET | `/api/v1/music/new?source=&page=1&pageSize=30` | 新歌列表 |
| GET | `/api/v1/music/artist?source=&ids=` | 批量歌手详情，`ids` 使用逗号分隔 |
| GET | `/api/v1/music/artist/works?source=&id=&type=song&page=1&pageSize=30` | 歌手歌曲或专辑作品，`type` 为 `song` 或 `album` |

#### 播放地址

请求示例：

```http
GET /api/v1/music/play?source=qq&id=003S1hgM2asCye&quality=320k
Authorization: Bearer <token>
```

参数约定：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `source` | 是 | `qq`、`netease` 或 `kuwo` |
| `id` | 是 | 对应平台歌曲 ID。QQ 使用 QQ Music 的 `mid`，例如 `003S1hgM2asCye` |
| `quality` | 否 | 未传时 QQ 默认为 `320k`，酷我默认为 `flac`，网易云默认为 `lossless` |

QQ 和酷我支持四档兼容值：

| 客户端 `quality` | QQ 巡回寺请求值 | 说明 |
| --- | --- | --- |
| `128k` | `standard` | 标准音质 |
| `320k` | `high` | 高品质 |
| `flac` | `high` | 参数合法；QQ 主解析返回官方直链，实际为 `320k` |
| `flac24bit` | `high` | 参数合法；QQ 主解析返回官方直链，实际为 `320k` |

巡回寺文档规定 `quality` 未传时默认 `high(320)`。QQ 主解析以可直接播放的 `music_url` 为准：四个客户端音质值都合法，但除 `128k` 映射为 `standard` 外，`320k`、`flac`、`flac24bit` 均映射为 `high`，返回巡回寺响应中的 `https://isure6.stream.qqmusic.qq.com/...` 官方流媒体临时地址。`requestedQuality` 保留客户端传值，`actualQuality` 返回实际的 `320k`。巡回寺主解析失败时，TuneFree 仍按原始请求音质继续降级解析。

网易云支持以下专属音质值，并兼容上面的四个历史值：

| 网易云 `quality` | 含义 | 兼容值 |
| --- | --- | --- |
| `standard` | 标准 | `128k` |
| `exhigh` | 极高 | `320k` |
| `lossless` | 无损 | `flac` |
| `hires` | Hi-Res | `flac24bit` |
| `jyeffect` | 环绕声 | `flac24bit` |
| `sky` | 杜比全景声 | `flac24bit` |
| `jymaster` | 超清母带 | `flac24bit` |

QQ 的主解析接口为巡回寺 QQ Music 接口（后端调用，前端不需要直接访问）：
`https://api.xunhuisi.store/API/QQMusic/Song.php`。QQ 播放解析顺序为：巡回寺主解析、TuneFree、QQ 文本接口、跨平台回退。主解析失败时会自动进入后续阶段。

网易云优先使用 ChKSz 解析，失败后使用 TuneFree 和跨平台回退；酷我使用 TuneFree 和跨平台回退。解析结果可能因版权、地区或上游资源状态低于请求音质。

成功响应示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "003S1hgM2asCye",
    "source": "qq",
    "actualSource": "qq",
    "name": "多远都要在一起",
    "artist": "G.E.M. 邓紫棋",
    "album": "新的心跳",
    "coverUrl": "https://y.gtimg.cn/music/photo_new/T002R300x300M000003c616O2Zlswm.jpg",
    "durationSec": null,
    "playUrl": "https://isure6.stream.qqmusic.qq.com/C600...",
    "requestedQuality": "320k",
    "actualQuality": "320k",
    "fileSize": null,
    "expireSec": null,
    "fromCache": false,
    "lyric": {
      "lineLyrics": "[00:00.00]多远都要在一起...",
      "karaokeLyrics": null
    }
  }
}
```

字段说明：

- `requestedQuality`：回显客户端请求的音质值。
- `actualQuality`：真正返回播放地址对应的音质，客户端应以此展示实际音质。
- `actualSource`：实际提供播放地址的平台；跨平台回退成功时可能与 `source` 不同。
- `playUrl`：带有效期或签名的临时地址，不要持久化；重新播放应重新请求 `/play`。
- `fromCache`：是否命中后端播放地址缓存。
- `lyric`：播放解析同时得到的歌词，可能只有普通歌词。完整的逐字歌词请调用 `/api/v1/music/lyric`；QQ、酷我、网易云是否有逐字时间取决于具体歌曲。

播放地址失败时，只有所有解析阶段都失败才返回错误。常见响应为：

```json
{
  "code": 1008,
  "message": "no playable url for qq:003S1hgM2asCye",
  "data": null
}
```

客户端不应根据 `requestedQuality` 推断资源一定达到目标音质，需优先使用 `actualQuality`；播放地址失效或过期时也应重新调用接口。

推荐歌单接口的 `limit` 默认为 `20`，后端会限制在 `1` 到 `50`。网易云使用匿名个性化推荐接口，QQ 使用首页热门推荐模块，酷我使用推荐歌单流；这些上游请求不携带音乐平台账号，因此不会根据本站用户的收藏和播放历史产生私人推荐。QQ 上游当前返回固定数量的首页卡片，实际 `list` 可能少于 `limit`。

```json
{
  "source": "qq",
  "category": "recommend",
  "order": "recommend",
  "page": 1,
  "pageSize": 20,
  "total": 12,
  "list": [
    {
      "id": "8075336924",
      "source": "qq",
      "name": "90后回忆 | MP3时代的流行歌曲",
      "coverUrl": "https://qpic.y.qq.com/music_cover/example/300?n=1",
      "description": "编辑推荐",
      "creatorName": "听风",
      "trackCount": null,
      "playCount": 145496069
    }
  ]
}
```

`total` 优先使用上游总数；上游未提供总数时为本次返回数量。推荐项的 `id` 可直接传给 `/api/v1/music/playlist/detail`。

歌曲对象（搜索、榜单详情、歌单详情、专辑详情、新歌和歌手歌曲作品中的 `list`/`songs`）包含：`id`、`source`、`name`、`artist`、`album` 等基础字段；歌手字段同时返回 `artistId`（主歌手 ID）和 `artistIds`（按 `artist` 中歌手顺序返回的全部歌手 ID，合作者也包含在内）。专辑搜索结果也返回同名的歌手字段。不同上游未提供歌手 ID 时，对应值为空。

`availableQualities` 来自对应平台官方 Web 元数据，可包含 `128k`、`320k`、`flac`、`flac24bit`。它只表示官方元数据中存在该规格，不表示当前用户可从官方免费播放，也不等同于实际解析接口一定能返回该规格。

`/play` 响应中的 `requestedQuality` 回显客户端请求值；网易云旧值不会被强制改写为新值。`actualQuality` 表示真正提供播放地址的上游音质，可能是网易云七档值，也可能在回退后成为四档兼容值。客户端应以 `actualQuality` 展示实际音质，不能把目标音质视为必然可用。

专辑详情响应：

```json
{
  "id": "0035Y3b01o1BsG",
  "source": "qq",
  "name": "梓渝",
  "coverUrl": "https://y.gtimg.cn/music/photo_new/T002R500x500M0000035Y3b01o1BsG.jpg",
  "description": null,
  "artist": "梓渝",
  "publishTime": "2026-08-05",
  "page": 1,
  "pageSize": 30,
  "total": 1,
  "list": []
}
```

歌手作品响应根据 `type` 填充 `songs` 或 `albums`，另一个数组为空：

```json
{
  "artistId": "336",
  "source": "kuwo",
  "type": "album",
  "page": 1,
  "pageSize": 20,
  "total": 45,
  "songs": [],
  "albums": [
    {
      "id": "1293",
      "source": "kuwo",
      "name": "叶惠美",
      "artist": "周杰伦",
      "artistId": "336",
      "artistIds": ["336"],
      "coverUrl": "https://img1.kuwo.cn/star/albumcover/240/s3s94/93/211513640.jpg",
      "publishTime": "2003-07-31",
      "trackCount": 11,
      "description": "..."
    }
  ]
}
```

酷我榜单上游不直接返回歌曲封面；榜单详情会按歌曲 ID 调用酷我官方图片服务补齐 `coverUrl`。图片解析采用并发请求和短期缓存，单个图片解析失败时该歌曲的 `coverUrl` 仍可能为空，不影响榜单其余数据返回。

歌词接口优先调用对应平台的官方接口：QQ 优先读取 QRC，酷我优先读取 LRCX，网易云读取 YRC。QQ、酷我获取不到有效逐字数据时保留普通 LRC。响应中的 `provider` 表示实际歌词来源，`official` 表示是否为平台官方来源；可能值包括官方的 `qq`、`netease`、`kuwo`，以及官方失败后使用的 `cyapi`、`tunefree` 兜底。QQ 和酷我保留短期第三方兜底，网易云只使用官方歌词接口。

`karaokeLyrics` 非空时，QQ、酷我已在后端转换为 App 支持的 YRC 格式：`[行开始毫秒,行持续毫秒](字开始绝对毫秒,字持续毫秒,0)文字…`。时间取自相同音源、相同歌曲 ID 的真实逐字数据，不按字数平均分配。`lineLyrics` 保留普通 LRC，供旧客户端或不支持逐字显示的页面回退。当前转换不附带翻译或罗马音。缓存有逐字数据时保留 6 小时，只有普通歌词时保留 10 分钟后重新检查。

```json
{
  "id": "631473518",
  "source": "kuwo",
  "lineLyrics": "[00:00.00]...",
  "karaokeLyrics": null,
  "provider": "kuwo",
  "official": true
}
```

音乐业务码：

| Code | 含义 |
| --- | --- |
| `1001` | 来源无效 |
| `1002` | 音质无效 |
| `1003` | 上游凭据未配置 |
| `1004` | 上游搜索失败 |
| `1005` | 上游播放失败 |
| `1006` | 上游歌词失败 |
| `1007` | 歌曲不存在 |
| `1008` | 没有可播放地址 |
| `1009` | 上游超时 |
| `1010` | 上游榜单失败 |
| `1011` | 上游歌单失败 |
| `1012` | 操作不支持 |

### 4.7 用户音乐库

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/user/music/history?page=0&size=20` | 播放历史 |
| DELETE | `/api/user/music/history/{id}` | 删除历史 |
| GET/POST | `/api/user/music/favorites` | 收藏列表或新增收藏 |
| DELETE | `/api/user/music/favorites?source=&songId=` | 取消收藏 |
| GET | `/api/user/music/favorites/status?source=&songId=` | 单曲收藏状态 |
| POST | `/api/user/music/favorites/status` | 批量收藏状态 |
| GET | `/api/user/music/shares/status?source=&songId=` | 单曲分享状态 |
| POST | `/api/user/music/shares` | 创建或更新单曲分享 |
| DELETE | `/api/user/music/shares?source=&songId=` | 关闭单曲分享 |
| POST | `/api/user/music/playlists/import` | 从分享链接导入歌单 |
| GET | `/api/user/music/playlist-favorites/status?source=&playlistId=` | 查询远端歌单收藏状态 |
| POST | `/api/user/music/playlist-favorites` | 收藏远端歌单 |
| DELETE | `/api/user/music/playlist-favorites?source=&playlistId=` | 取消收藏远端歌单 |
| POST | `/api/user/music/playlists/{id}/sync` | 同步远端歌单 |
| GET/POST | `/api/user/music/playlists` | 歌单列表或创建本地歌单 |
| GET/PATCH/DELETE | `/api/user/music/playlists/{id}` | 歌单详情、重命名、删除 |
| POST | `/api/user/music/playlists/{id}/items` | 添加歌曲 |
| DELETE | `/api/user/music/playlists/{id}/items/{itemId}` | 移除歌曲 |
| GET | `/api/public/music/share/{token}` | 公开读取单曲分享 |

收藏远端歌单支持 `qq`、`netease`、`kuwo`。收藏成功后，歌单及最多 1000 首歌曲会保存到当前用户的“我的歌单”；重复收藏返回同一条歌单，不会重复创建。

收藏状态：

```http
GET /api/user/music/playlist-favorites/status?source=qq&playlistId=123
Authorization: Bearer <token>
```

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "source": "qq",
    "playlistId": "123",
    "favorite": true,
    "userPlaylistId": 42
  }
}
```

未收藏时 `favorite` 为 `false`，`userPlaylistId` 为 `null`。

收藏歌单：

```http
POST /api/user/music/playlist-favorites
Authorization: Bearer <token>
Content-Type: application/json

{"source":"qq","playlistId":"123"}
```

响应 `data` 为“我的歌单”列表使用的 `ImportedPlaylist` 对象。取消收藏调用：

```http
DELETE /api/user/music/playlist-favorites?source=qq&playlistId=123
Authorization: Bearer <token>
```

取消收藏会删除该用户保存的歌单及其歌曲；删除其他用户的收藏不受影响。未收藏时取消会返回 HTTP `404`。

### 4.8 会员与兑换码

用户接口：

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/user/membership` | 当前会员状态 |
| POST | `/api/user/membership/redeem` | 兑换会员码，body 为 `{"code":"OW-..."}` |

没有有效会员时调用播放接口，返回 HTTP `403`：

```json
{
  "code": 403,
  "message": "需要有效会员才能播放，请先兑换会员卡",
  "data": null
}
```

管理员接口：

| Method | Path | 说明 |
| --- | --- | --- |
| POST | `/api/admin/membership/batch-extend` | 为多个用户批量增加会员时长 |
| POST | `/api/admin/redeem-codes` | 批量生成兑换码 |
| GET | `/api/admin/redeem-codes?type=&status=&page=0&size=20` | 查询兑换码和兑换用户 |
| POST | `/api/admin/redeem-codes/{id}/revoke` | 撤销未使用兑换码 |
| DELETE | `/api/admin/redeem-codes/{id}` | 删除已撤销兑换码 |

批量续期请求：

```json
{
  "userIds": [1, 2, 3],
  "type": "MONTH"
}
```

`type` 可选 `DAY`、`WEEK`、`MONTH`、`YEAR`、`LIFETIME`。该接口仅管理员可调用。

兑换码生成请求：

```json
{
  "type": "MONTH",
  "quantity": 10
}
```

`type` 可选 `DAY`、`WEEK`、`MONTH`、`YEAR`、`LIFETIME`，单次生成 1 到 100 个。查询响应的每条记录包含 `code`、`status`、`redeemedByUserId`、`redeemedByUsername`、`redeemedByEmail`、`redeemedAt` 等字段。新数据的 `code` 是明文；迁移前未保存明文的历史数据无法恢复，只能返回 `codePreview`。

### 歌手详情接口

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/v1/music/artist?source=qq&ids=123456,789012` | 获取歌手详情（支持批量 `ids`） |

**响应字段**（包括 `description`）：
```json
{
  "id": "123456",
  "source": "qq",
  "name": "周杰伦",
  "coverUrl": "https://y.gtimg.cn/...",
  "description": "周杰伦（Jay Chou），1979年1月18日出生于台湾省新北市，华语流行男歌手、演员、词曲创作人...",
  "songCount": 500,
  "albumCount": 20
}
```

**description 字段来源**：
- **Kuwo**：搜索结果 `desc` 字段（完整长文）
- **Netease**：`https://music.163.com/api/v1/artist/{id}` 的 `briefDesc` 字段
- **QQ**：`fcg_v8_singer_detail_cp.fcg` 的 `singerBrief` 字段

**请求示例**：
```http
GET /api/v1/music/artist?source=netease&ids=6452
Authorization: Bearer <token>
```

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "id": "6452",
      "source": "netease",
      "name": "周杰伦",
      "description": "周杰伦（Jay Chou），1979年1月18日出生于台湾省新北市...",
      "songCount": 568,
      "albumCount": 44
    }
  ]
}
```

**请求示例**：
```http
GET /api/v1/music/artist?source=kuwo&ids=336
Authorization: Bearer <token>
```

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    {
      "id": "336",
      "source": "kuwo",
      "name": "周杰伦",
      "description": "周杰伦（Jay Chou），1979年1月18日出生于台湾省新北市，华语流行男歌手...",
      "songCount": 1698,
      "albumCount": 45
    }
  ]
}
```

### 4.9 管理员用户与系统配置

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/admin/users?keyword=&role=&enabled=&page=0&size=20` | 用户分页列表 |
| GET | `/api/admin/users/{id}` | 用户详情 |
| POST | `/api/admin/users` | 创建用户 |
| PUT | `/api/admin/users/{id}` | 修改用户 |
| POST | `/api/admin/users/{id}/reset-password` | 管理员重置用户密码 |
| DELETE | `/api/admin/users/{id}` | 删除用户 |
| GET/POST | `/api/admin/configs` | 系统配置列表或创建配置 |
| GET/PUT/DELETE | `/api/admin/configs/{id}` | 配置详情、修改、删除 |

前端可通过 `/api/user/me` 返回的 `role` 或 `canManageSystemConfig` 控制管理员入口显隐。

### 4.10 内容工厂

以下接口仅管理员可用：

| Method | Path | 说明 |
| --- | --- | --- |
| GET | `/api/admin/content/status` | 内容工厂状态 |
| GET | `/api/admin/content/hot` | 热点列表 |
| GET | `/api/admin/content/articles` | 文章列表 |
| POST | `/api/admin/content/articles/generate` | 同步生成文章 |
| POST | `/api/admin/content/articles/generate-async` | 异步生成文章 |
| GET/PUT/DELETE | `/api/admin/content/articles/{id}` | 文章详情、修改、删除 |
| POST | `/api/admin/content/articles/{id}/revise` | AI 修订文章 |
| POST | `/api/admin/content/articles/{id}/visuals` | 生成文章配图 |
| POST | `/api/admin/content/articles/{id}/wechat-draft` | 创建微信草稿 |
| POST | `/api/admin/content/articles/{id}/publish` | 发布文章 |
| GET | `/api/admin/content/automation` | 自动化配置 |
| POST | `/api/admin/content/automation/run` | 执行自动化任务 |
| GET | `/api/admin/content/automation/run/{jobId}` | 查询任务状态 |
| POST | `/api/admin/content/automation/jobs/retry` | 重试失败任务 |
| GET | `/api/v1/content/assets/{filename}` | 公开读取内容附件 |

### 4.11 网飞猫影视目录与播放

影视接口统一位于 `/api/v1/videos/**`，所有目录、分类、筛选、搜索和详情请求都需要登录；只有 `/detail` 之外的播放地址解析还需要有效会员。客户端应沿用音乐接口的 Bearer Token 和统一响应外壳。

| Method | Path | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/videos/categories` | 登录 | 分类列表，当前包含电影、连续剧、动漫、综艺纪录、短剧 |
| GET | `/api/v1/videos/filters` | 登录 | 年份、地区、类型、排序选项 |
| GET | `/api/v1/videos/discover?page=1&pageSize=20&category=&year=&region=&genre=&order=` | 登录 | 分类发现和筛选，`page` 从 1 开始，`pageSize` 会限制在 1~50 |
| GET | `/api/v1/videos/search?keyword=&category=&page=1&pageSize=20` | 登录 | 影视搜索，关键词最多 80 个字符 |
| GET | `/api/v1/videos/detail?id=333692` | 登录 | 详情、标签、线路和选集 |
| GET | `/api/v1/videos/{id}/detail` | 登录 | 详情的 REST 风格兼容路径 |
| GET | `/api/v1/videos/play?id=333692&episode=41&line=3537870` | 会员 | 解析播放页中的直链或返回播放页回退地址 |
| GET | `/api/v1/videos/{id}/play?episode=41&line=3537870` | 会员 | 播放的 REST 风格兼容路径 |

发现和搜索返回的数据结构如下：

```json
{
  "list": [{
    "id": "333692",
    "title": "消失的人",
    "posterUrl": "https://www.ncat21.com/upload/poster.jpg",
    "detailUrl": "https://www.ncat21.com/detail/333692.html",
    "status": "正片",
    "description": "简介"
  }],
  "page": 1,
  "pageSize": 20,
  "total": 1,
  "hasMore": false
}
```

播放响应包含 `playable`、`mediaUrl`、`format`（`mp4`、`hls` 或 `dash`）、`embedUrl` 和 `upstreamUrl`。客户端只能把 HTTPS 且格式为 MP4/HLS/DASH 的 `mediaUrl` 交给 `video_player`；`playable=false` 或格式不受支持时，使用经过域名校验的 `upstreamUrl`/`embedUrl` 打开播放页。ECS 不代理媒体字节，也不会返回任意外域名的播放地址。上游目录降级为内置分类，详情、搜索或播放上游不可用时返回 HTTP/业务码 `502`。

影视上游地址由 ECS 环境变量配置：`APP_VIDEO_NCAT21_BASE_URL`，默认 `https://www.ncat21.com`；该值必须是 HTTPS 的 ncat21 主域名或子域名，否则 ECS 启动时拒绝配置。`APP_VIDEO_CACHE_MINUTES` 默认 5 分钟。后端禁止跟随任意重定向。目标站点存在反自动化和域名稳定性风险，生产上线前应确认法律授权、DNS、证书和上游可用性。

投屏能力是平台相关的：iOS 使用 AirPlay `AVRoutePickerView`，只有原生路由回调确认后才显示已连接；macOS 使用原生 `AVPlayer` 接管 AirPlay 播放并暂停 Flutter 本地播放器；Android 当前明确返回“未配置 Google Cast 接收端”，Windows 在没有原生通道时显示不支持。所有平台都保留在 ncat21 播放页打开的安全回退，不把打开选择器误报为连接成功。

## 5. 前端请求封装建议

TypeScript 示例：

```ts
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = localStorage.getItem("accessToken");
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  const body = (await response.json()) as ApiResponse<T>;
  if (!response.ok || body.code !== 0) {
    throw Object.assign(new Error(body.message || "请求失败"), {
      status: response.status,
      code: body.code,
    });
  }
  return body.data;
}
```

刷新令牌请求需要防止并发。多个请求同时遇到 `401` 时，只允许发起一次 refresh，其余请求等待该 Promise，刷新成功后再各自重试一次。

## 6. 联调检查清单

1. 生产环境请求地址不要包含 Markdown 链接语法，只传真实 URL。
2. JSON 请求设置 `Content-Type: application/json`，上传文件时让浏览器自动设置 multipart boundary。
3. 登录请求只传 `username` 或 `email` 之一。
4. 注册和找回密码分别调用各自的验证码接口，不复用验证码。
5. 刷新成功后同时替换访问令牌和刷新令牌。
6. 密码修改或重置成功后清理旧会话。
7. 管理员页面处理 `403`，普通用户页面处理 `401`。
8. SSE、音频和文件下载不要按普通 JSON 响应解析。
