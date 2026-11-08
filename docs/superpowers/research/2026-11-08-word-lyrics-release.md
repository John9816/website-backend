# 逐字歌词后端发布

用户于 2026-11-08 明确要求“都发布并推送一下”。

## 发布对象

- 仓库：John9816/website-backend。
- 分支：`codex/tvbox-provider`。
- 源码提交：`15dc6f75b8074486e60831587f72f5b1afac63c1`，已推送。
- ECS 发布目录：`/opt/website/releases/20261108T065300Z-word-lyrics-15dc6f75b807`。
- 实际服务启动时间：2026-11-08 14:51:37（Asia/Shanghai）。目录名称仅为发布标识，以实际激活时间为准。
- JAR SHA-256：`5b732528762366dfc6d0f2b5b846a8605849836eb5472a7728ff81b32dcbe214`。
- 回滚目录：`/opt/website/releases/20261108T021619Z-xuecheng-themes-4ae2c02fd5f8`。

## 发布前验证

- `mvn -q clean verify` 成功，340 项测试，0 失败、0 错误、0 跳过。
- 新旧 JAR 逐项 SHA-256 对比：仅新增 `QrcCipher` / `WordLyricCodec` 相关 class，修改 QQ、酷我客户端及 MusicService 相关 class；没有其他配置/数据库结构变化。
- 数据库备份：`/opt/backups/website-before-word-lyrics-20261108.sql.gz`，备份 website 与 website_java，443655 字节，解压后 6455419 字节；gzip 完整性及 dump 完成标记均验证通过。
- 使用发布锁、旧版本前置校验、JAR 哈希校验与失败自动回滚；复用当前前端文件及服务配置，原子切换 current，只重启 API 服务。

## 线上功能核验

| 来源 | 歌曲 ID | 歌词行 | 字词时间片 |
| --- | --- | ---: | ---: |
| QQ | 003UkWuI0E8U0l | 69 | 589 |
| QQ | 0039MnYb0qxYhV | 63 | 602 |
| 酷我 | 198554068 | 69 | 589 |
| 酷我 | 228908 | 63 | 602 |

四次读取均通过公开的 `/api/v1/music/lyric`，返回 `official=true`、正确 provider 和非空逐字歌词。三源搜索及网易原有逐字歌词接口回归通过。

证据位于 DuckMusic 工作区 `build/qa/release-1.1.49-2026-11-08/`，包括 `backend-jar-diff.json`、`backend-tests.json`、`backend-deploy.log`、`backend-production-verification.json`、`backend-regression-checks.json` 和连续监控 `backend-monitor.jsonl`。

## 稳定性观察

完成激活后至少 15 分钟观察，最后采样为 2026-11-08 15:06:50（Asia/Shanghai）。所有采样的 API、网页及代理接口均为 HTTP 200，服务 MainPID 始终为 323157，NRestarts 为 0。最终内存约 401 MiB。观察期错误与警告日志均未发现异常计数；功能验证通过后保持新版本运行。
