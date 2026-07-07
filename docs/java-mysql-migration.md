# Java + MySQL migration notes

## Current production state

- Production API still runs the Node compatibility service migrated from Cloudflare Worker.
- Java backend is being aligned in parallel and should be deployed on a separate port first.
- Do not switch Nginx `/api` to Java until MySQL data migration and smoke tests pass.

## API alignment completed

- `/health`
- `/api/auth/*`
- `/api/user/me`
- `/api/admin/change-password`
- `/api/user/change-password`
- `/api/public/categories`
- `/api/public/links`
- `/api/public/nav`
- `/api/admin/categories/*`
- `/api/user/categories/*`
- `/api/admin/links/*`
- `/api/user/links/*`
- `/api/admin/configs/*`
- `/api/admin/image/*`
- `/api/user/image/*`
- `/api/public/image/shared`
- `/api/v1/image/file/*`
- `/api/user/ai/*`
- `/api/admin/kb/*`
- `/api/user/kb/*`
- `/api/public/kb/share/*`
- `/api/v1/kb/assets/*`
- `/api/v1/content/assets/*`
- `/api/v1/music/*`
- `/api/user/music/*`
- `/api/public/music/share/*`

## Content factory status

The Java backend now exposes the same content factory route shape used by the frontend:

- `/api/admin/content/status`
- `/api/admin/content/hot`
- `/api/admin/content/articles`
- `/api/admin/content/articles/generate`
- `/api/admin/content/articles/{id}`
- `/api/admin/content/articles/{id}/wechat-draft`
- `/api/admin/content/articles/{id}/publish`
- `/api/admin/content/automation`
- `/api/admin/content/automation/jobs/retry`

Current Java implementation is a migration-safe minimum:

- Persists articles to MySQL table `content_article`.
- Generates editable local drafts.
- Supports list/get/update/delete.
- Supports local status transitions for WeChat draft/publish.
- Does not yet call the AI research/generation pipeline from the Worker.
- Does not yet call real WeChat draft or publish APIs.

## Verification

Local Java verification:

```powershell
& "$env:LOCALAPPDATA\CodexTools\apache-maven-3.9.11\bin\mvn.cmd" test
```

Latest result:

- 51 tests passed.
- Build success.

## Recommended rollout

1. Install and initialize MySQL on ECS.
2. Convert current SQLite data to MySQL.
3. Deploy Java backend as `website-java-api.service` on `127.0.0.1:8082`.
4. Run smoke tests against Java directly.
5. Compare selected API responses between Node `8081` and Java `8082`.
6. Switch Nginx `/api` upstream to Java only after smoke tests pass.
7. Keep Node service running as rollback during the first observation window.
