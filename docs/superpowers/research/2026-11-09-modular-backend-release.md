# 后端模块化发布记录

2026-11-09，Asia/Shanghai。后端于 02:05:06 启动，网页服务于 02:05:19 启动。

## 版本与产物

- 后端代码：`46aeee7e823e263c4fcf140abd7f0efc40ca131a`，分支 `codex/tvbox-provider`，已推送 `John9816/website-backend`。
- 前端代码：`d0bfbcf5eb9c355efadc3512647cc011974e19f8`，分支 `codex/xuecheng-editor`，已推送 `John9816/website`。
- 当前发布：`/opt/website/releases/20261108T180346Z-modules-46aeee7e-d0bfbcf5`。
- 回滚版本：`/opt/website/releases/20261108T173544Z-qishui-b1a09109-d0bfbcf5`。
- JAR SHA-256：`c6ab23d2f7aab0e6aac76d2ac30b1f4e094f7df58f8b57348ca35f6d230de966`。
- 发布包 SHA-256：`830a2f40258f0b44450862831b681817e24459875738625f230ec671db99e563`。

前端复用上一版本已验证的 dist/server 文件，运行文件与已提交版本一致。前端原有下载镜像、Nginx、部署文档等未提交修改保持原样，不包含在运行包中。元数据明确区分工作区 dirty 与运行文件 clean。

## 发布过程

验证上传包与全部包内文件的 SHA-256，持有 `/opt/website/.deploy.lock` 并校验 current 前置版本。两份 systemd service 文件与线上完全相同；通过既有激活脚本原子切换 current，服务检查失败自动恢复旧版本。保留 Nginx、共享上传目录、下载镜像及环境配置。

本次模块化没有新增 SQL 迁移，189 条路由映射、140 个 JSON 模型、34 个 JPA 实体与重构前契约一致。黑源初次接入的 `user_qishui_account` 表及配置已在前一版本安装，详见 [黑源首次上线记录](2026-11-09-qishui-web-release.md)。

## 验证结果

- 后端 `clean verify`：455 项测试，零失败、零错误、零跳过。
- `ModuleBoundaryTests`：16 个业务模块，显式依赖检查通过，无循环；控制器、领域、仓储和公共层方向检查通过。
- `ArchitectureContractTests`：原 HTTP/JSON/实体契约匹配，不更新基线。
- 一次性方法体对照：内容 119 个方法体保持；generate 只委托独立 writer；播放方法体、管理员清理 SQL 和执行顺序保持。
- 并发证据：12 个相同歌单冷请求为 1 次上游调用；12 个导航冷请求为 2 次批量 SQL；异常重试、TTL、不同键并行、深复制、提交/回滚和旧令牌错误隔离通过。
- 公网 API health、网页 healthz、同源导航正常，导航返回 9 个分类。
- 真实接口验证使用两个短期临时账号，JWT 只在服务器内存中使用，测试后确认账号删除。用户信息、分类、链接、AI 会话、图片历史、音乐收藏/歌单/历史、会员等 9 个读取接口正常；普通用户访问管理员接口 403，未登录搜索 401。
- 黑源真实搜索返回 3 条：本次冷请求 1369ms，随后缓存请求 11ms；歌词存在。该小样本仅作功能检查，不作为性能百分比或 P95 指标。
- 真正创建 PNG 二维码，轮询状态 new；另一用户读取 404，解绑后旧二维码会话 404；公开响应不含个人凭据。
- 线上网页桌面/手机浏览器合成测试通过：搜索类型归一、复合歌单 ID、扫码 scanned/confirmed、刷新、解绑、关闭取消、切换账号隔离、加密音频错误显示。截图已检查；二维码图片是测试占位图，不能用于真人授权。
- 部署后检查两服务 active/running，NRestarts=0；观察窗口内没有 ERROR、数据库连接/SQL 语法错误或 OOM。

## 仍需真实凭据验证的范围

生产未配置 `QISHUI_PARSE_ACCESS_TOKEN`，播放接口明确返回 1003。完整播放没有验证；CENC 加密音频仍明确不支持直接网页播放。真人扫码确认后的账号绑定/刷新尚未端到端实测，浏览器该部分为合成响应验证。用户个人扫码授权与解析服务 Bearer 凭据是不同层次，不能相互替代。

审计日志保存在本机 `/tmp/modular-production-smoke.json`、`/tmp/website-modular-production-browser.log`、`/tmp/website-modular-production-qa/`；未提交真实二维码、个人授权、JWT 或服务器配置秘密。
