# Cloudflare 部署文档

本文档说明如何把本项目部署到 Cloudflare。推荐架构如下：

- 前端：Cloudflare Pages
- 后端 API：Cloudflare Workers，代码位于 `cloudflare-worker/`
- 数据库：Cloudflare D1
- 缓存/临时状态：Cloudflare KV
- 图片文件：Cloudflare R2，可选

Spring Boot 后端仍保留在仓库中；Cloudflare Worker 是面向边缘部署的第二套后端实现。部署后，前端只需要把 API Base URL 指向 Worker 地址。

## 1. 前置要求

本地需要准备：

- Node.js 18+
- npm
- Cloudflare 账号
- Wrangler CLI，项目内已通过 `cloudflare-worker/package.json` 引入

进入 Worker 目录并安装依赖：

```powershell
cd cloudflare-worker
npm install
```

登录 Cloudflare：

```powershell
npx wrangler login
```

如果当前终端环境不方便打开浏览器，可以改用 API Token 登录：

```powershell
npx wrangler login --browser=false
```

## 2. 创建 Cloudflare 资源

在 `cloudflare-worker/` 目录执行：

```powershell
npx wrangler d1 create website_db
npx wrangler kv namespace create APP_KV
```

如需保存生成图片文件，再创建 R2 bucket：

```powershell
npx wrangler r2 bucket create website-assets
```

注意：R2 需要先在 Cloudflare Dashboard 中启用。没有启用 R2 时，可以暂时不配置 `r2_buckets`，Worker 仍可部署，base64 图片会退回保存在 D1 的 `imageData` 字段中。

## 3. 配置 `wrangler.jsonc`

编辑 `cloudflare-worker/wrangler.jsonc`。

确认 Worker 名称：

```jsonc
{
  "name": "website-api"
}
```

把 `wrangler d1 create` 返回的 `database_id` 填入：

```jsonc
"d1_databases": [
  {
    "binding": "DB",
    "database_name": "website_db",
    "database_id": "<your-d1-database-id>"
  }
]
```

把 `wrangler kv namespace create` 返回的 `id` 填入：

```jsonc
"kv_namespaces": [
  {
    "binding": "APP_KV",
    "id": "<your-kv-namespace-id>"
  }
]
```

如果启用 R2，追加：

```jsonc
"r2_buckets": [
  {
    "binding": "R2_BUCKET",
    "bucket_name": "website-assets"
  }
]
```

## 4. 配置环境变量和密钥

普通变量在 `wrangler.jsonc` 的 `vars` 中配置：

```jsonc
"vars": {
  "CORS_ORIGINS": "http://localhost:5173,https://*.pages.dev",
  "PUBLIC_R2_BASE_URL": "",
  "AI_CHAT_BASE_URL": "",
  "IMAGE_API_BASE_URL": "",
  "MUSIC_BACKEND_BASE_URL": "",
  "MUSIC_PROXY_BASE_URL": ""
}
```

建议按实际环境调整：

- `CORS_ORIGINS`：允许访问 API 的前端域名。上线后建议加上正式 Pages 域名或自定义域名。
- `PUBLIC_R2_BASE_URL`：R2 公开访问域名，例如自定义域名 `https://assets.example.com`。
- `AI_CHAT_BASE_URL`：OpenAI 兼容聊天接口地址，例如 `https://api.openai.com/v1`。
- `IMAGE_API_BASE_URL`：图片生成接口地址。
- `MUSIC_BACKEND_BASE_URL`：公开可访问的 Spring Boot 后端地址，用于代理 `/api/v1/music/*`。
- `MUSIC_PROXY_BASE_URL`：兼容旧配置，等同于 `MUSIC_BACKEND_BASE_URL`。

敏感信息用 Wrangler Secret 写入，不要提交到代码仓库：

```powershell
npx wrangler secret put JWT_SECRET
npx wrangler secret put AI_CHAT_API_KEY
npx wrangler secret put IMAGE_API_KEY
npx wrangler secret put ADMIN_DEFAULT_USERNAME
npx wrangler secret put ADMIN_DEFAULT_PASSWORD
```

必配项：

- `JWT_SECRET`：JWT 签名密钥，生产环境必须使用高强度随机值。

可选项：

- `AI_CHAT_API_KEY`：启用 AI 对话/TTS 时配置。
- `IMAGE_API_KEY`：启用图片生成/编辑时配置。
- `ADMIN_DEFAULT_USERNAME`：首次自动创建管理员的用户名。
- `ADMIN_DEFAULT_PASSWORD`：首次自动创建管理员的密码。

如果不设置管理员默认值，首次请求时会自动创建：

- 用户名：`admin`
- 密码：`admin123`

上线后必须立即修改默认密码。

## 5. 初始化 D1 数据库

执行远程 D1 migration：

```powershell
npm run db:migrate:remote
```

本地开发可使用：

```powershell
npm run db:migrate:local
```

当前 migration 文件位于：

```text
cloudflare-worker/migrations/0001_initial.sql
```

## 6. 从 MySQL 导入已有数据

如果需要把 Spring Boot 版本的 MySQL 数据导入 D1，使用脚本：

```text
cloudflare-worker/scripts/mysql_to_d1.py
```

先编辑脚本中的 `MYSQL` 连接信息，确认数据库地址、账号、密码和库名正确。然后在仓库根目录执行：

```powershell
python cloudflare-worker/scripts/mysql_to_d1.py
```

脚本会生成：

```text
cloudflare-worker/.wrangler/mysql-import.sql
```

导入远程 D1：

```powershell
cd cloudflare-worker
npx wrangler d1 execute website_db --remote --file .wrangler/mysql-import.sql
```

注意事项：

- D1 是 SQLite，不是 MySQL，字段类型和冲突处理已在 migration 中做了转换。
- Worker 新账号使用 SHA-256 密码哈希；Java 后端历史用户如果是 BCrypt 哈希，不能直接登录，需重置密码或增加一次性兼容迁移。
- 导入前建议先备份 D1，避免重复导入或覆盖关键数据。

## 7. 本地验证 Worker

在 `cloudflare-worker/` 目录运行：

```powershell
npm run typecheck
npm run dev -- --port 8787 --local
```

Windows 沙箱或受限环境下，Wrangler 可能无法写入用户目录日志。可以先把 Wrangler 配置目录放到项目内：

```powershell
$env:XDG_CONFIG_HOME = Join-Path (Get-Location) ".wrangler-config"
npm run dev -- --port 8787 --local
```

验证健康检查：

```powershell
curl.exe http://127.0.0.1:8787/health
```

预期返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "status": "up",
    "runtime": "cloudflare-workers"
  }
}
```

## 8. 部署 Worker

在 `cloudflare-worker/` 目录执行：

```powershell
npm run deploy
```

部署完成后，记录 Worker 地址，通常类似：

```text
https://website-api.<your-subdomain>.workers.dev
```

验证线上健康检查：

```powershell
curl.exe https://website-api.<your-subdomain>.workers.dev/health
```

## 9. 部署前端到 Cloudflare Pages

前端项目可以独立部署到 Cloudflare Pages。按 Pages 项目设置填写：

- Framework preset：按前端实际框架选择，Vite 项目通常选择 `Vite`
- Build command：通常为 `npm run build`
- Build output directory：Vite 通常为 `dist`

在 Pages 的环境变量中配置前端 API 地址。变量名以实际前端代码为准，常见形式是：

```text
VITE_API_BASE_URL=https://website-api.<your-subdomain>.workers.dev
```

部署 Pages 后，把 Pages 域名加入 Worker 的 `CORS_ORIGINS`：

```jsonc
"CORS_ORIGINS": "https://<your-pages-project>.pages.dev,https://www.example.com"
```

然后重新部署 Worker：

```powershell
cd cloudflare-worker
npm run deploy
```

## 10. 自定义域名

推荐域名规划：

- 前端：`https://www.example.com`
- API：`https://api.example.com`
- R2 静态资源：`https://assets.example.com`

在 Cloudflare Dashboard 中分别给 Pages、Workers、R2 绑定自定义域名。绑定完成后：

1. 前端环境变量改为 `VITE_API_BASE_URL=https://api.example.com`
2. Worker 的 `CORS_ORIGINS` 加入前端正式域名
3. Worker 的 `PUBLIC_R2_BASE_URL` 改为 R2 公开域名
4. 重新部署前端和 Worker

## 11. 上线检查清单

上线前确认：

- `JWT_SECRET` 已设置为生产密钥。
- 默认管理员密码已修改。
- `CORS_ORIGINS` 只包含可信前端域名。
- D1 migration 已在 remote 环境执行。
- 如需历史数据，MySQL 到 D1 导入已完成并抽查。
- AI、图片、音乐代理相关变量已按需配置。
- R2 如已启用，`R2_BUCKET` 和 `PUBLIC_R2_BASE_URL` 均可用。
- `/health` 返回正常。
- 前端登录、导航、图片、AI、知识库、音乐等主要路径已完成冒烟测试。

## 12. 常见问题

### CORS 报错

检查 `wrangler.jsonc` 中的 `CORS_ORIGINS` 是否包含当前前端域名。多个域名用英文逗号分隔：

```text
https://<project>.pages.dev,https://www.example.com
```

### D1 表不存在

通常是 remote migration 未执行：

```powershell
cd cloudflare-worker
npm run db:migrate:remote
```

### 图片生成成功但图片打不开

如果上游返回 base64，Worker 会优先写入 R2。检查：

- `r2_buckets` 是否配置了 `R2_BUCKET`
- R2 bucket 是否已创建
- `PUBLIC_R2_BASE_URL` 是否可公开访问

未配置 R2 时，图片会退回保存在 D1 中。

### 音乐搜索或播放不可用

Worker 没有完整重写 Java 后端的音乐聚合逻辑。需要配置可公网访问的 Spring Boot 后端：

```jsonc
"MUSIC_BACKEND_BASE_URL": "https://api-java.example.com"
```

### 线上仍然访问 localhost

检查前端 Pages 环境变量，确保 API Base URL 指向 Worker 或自定义 API 域名，而不是：

```text
http://localhost:8080
```

## 13. 回滚

Worker 回滚：

```powershell
npx wrangler deployments list
npx wrangler rollback
```

如果是配置错误，优先修正 `wrangler.jsonc` 或 Secret 后重新部署：

```powershell
npm run deploy
```

D1 数据变更没有简单的自动回滚机制。执行导入、批量更新或删除前，应先导出备份。
