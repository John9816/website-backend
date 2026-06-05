# Cloudflare Worker Backend

Full Chinese deployment guide: [`../docs/cloudflare-deployment.md`](../docs/cloudflare-deployment.md).

This directory is a second backend for the existing Java project. It keeps the current API path shape where practical and targets:

- Frontend: Cloudflare Pages
- Backend: Cloudflare Workers
- Database: Cloudflare D1
- Storage: Cloudflare R2
- Cache: Cloudflare KV

The Spring Boot backend is left intact. Switch the frontend API base URL to this Worker after deployment.

## What Is Implemented

- JWT login/register/current user/change password
- User credits, daily check-in, and image generation credit charging
- Public and authenticated nav categories/links
- `sys_config` CRUD
- Image generation tasks/history/public sharing with generated outputs stored in R2
- AI conversations/messages/models/voices/TTS using an OpenAI-compatible upstream
- KB spaces/docs/tags/versions/public share
- User music history/favorites/shares/playlists
- Public music endpoints implemented directly in the Worker for QQ/Netease/Kuwo metadata and TuneFree play URLs

## Setup

Install dependencies:

```bash
npm install
```

Create Cloudflare resources:

```bash
npx wrangler d1 create website_db
npx wrangler kv namespace create APP_KV
npx wrangler r2 bucket create website-assets
```

Copy the returned IDs into `wrangler.jsonc`.

R2 must be enabled once in the Cloudflare Dashboard before `wrangler r2 bucket
create` works. The Worker requires the `R2_BUCKET` binding for image generation
and image editing results. Upstream image URLs are downloaded and written to R2;
base64 upstream images are decoded and written to R2.

Set secrets:

```bash
npx wrangler secret put JWT_SECRET
npx wrangler secret put AI_CHAT_API_KEY
npx wrangler secret put IMAGE_API_KEY
```

Optional variables in `wrangler.jsonc`:

- `CORS_ORIGINS`: comma-separated frontend origins, supports `https://*.pages.dev`
- `PUBLIC_R2_BASE_URL`: optional public/custom domain for R2 assets. If empty,
  image URLs use the Worker proxy path `/api/v1/image/file/:filename`.
- `AI_CHAT_BASE_URL`: OpenAI-compatible base URL, for example `https://api.openai.com/v1`
- `IMAGE_API_BASE_URL`: image generation endpoint
- Image credits read these D1 `sys_config` keys: `user.register.initial_credits` (default `10`), `image.generate.credit_cost` (default `1`), and `user.checkin.reward_credits` (default `5`)
- Music play reads TuneFree settings from D1 `sys_config`: `music.tunefree.account`, `music.tunefree.password`, `music.tunefree.udid`, and `music.tunefree.token`

Apply D1 schema:

```bash
npm run db:migrate:local
npm run db:migrate:remote
```

Run locally:

```bash
npm run dev
```

On Windows sandboxed shells, Wrangler may fail while writing logs under the
user profile. Keep its local config/log path inside this project before running
Wrangler commands:

```powershell
$env:XDG_CONFIG_HOME = Join-Path (Get-Location) ".wrangler-config"
npm run dev -- --port 8787 --local
```

Deploy:

```bash
npm run deploy
```

## Default Admin

On the first request, the Worker creates an admin user if the user table is empty:

- username: `admin`
- password: `admin123`

Override with Worker secrets or vars:

- `ADMIN_DEFAULT_USERNAME`
- `ADMIN_DEFAULT_PASSWORD`

Change this password immediately after first login.

## Compatibility Notes

The Worker uses SHA-256 password hashes for new accounts, not the BCrypt hashes from the Java backend. Existing MySQL users cannot be copied directly unless you reset passwords or add a one-time migration that verifies old hashes before rewriting them.

D1 is SQLite, so the schema uses `INTEGER PRIMARY KEY AUTOINCREMENT`, `TEXT`, and `ON CONFLICT` instead of MySQL-specific syntax.

Music provider logic is implemented directly in the Worker for QQ, Netease, and Kuwo metadata endpoints. Play URL resolution uses TuneFree credentials/token from D1 `sys_config`; personal music library features run fully on D1.

AI streaming keeps the same SSE route and emits a compact final assistant event after persistence. If you need token-by-token streaming, wire the upstream streaming response through `ai.streamMessage`.
