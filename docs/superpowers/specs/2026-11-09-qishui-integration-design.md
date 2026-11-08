# 汽水黑源后端适配设计

目标：调查本机 TuneFreeNext 黑源，形成接口文档，适配现有 Spring Boot 后端，验证后提交并推送当前 GitHub 分支。用户要求每个用户独立扫码，且后续工作不使用子代理。

最终协议见 `docs/tunefree-next-qishui-api.md`，证据来自指定安装包、手工LLDB映射和实际HTTP请求。

## 设计

- `qishui` 是第四个音乐源，接受 `qs`；长ID保持字符串。
- 曲库 client token、用户扫码凭据、TuneFree v1解析凭据分离。个人扫码用于用户账号，不将所有目录操作误归为个人权限。
- `QishuiHttpClient` 限定上游目的端，关闭重定向和自动重试，复用第三方音乐超时配置，每个请求受总预算及取消约束，限制响应体并脱敏错误。
- `QishuiMusicClient` 处理曲库 token、歌曲/歌单搜索、媒体歌词、歌单详情和歌手歌曲；公开目录可沿用全局短缓存。
- `QishuiGuard` 使用JCA P-256/SHA-256 ECDSA，DER签名，PKCS#10 CSR；恢复的私钥只用于其用户绑定。
- `QishuiAuthClient` 处理native QR、access code、ticket、个人资料及刷新；应用标识从环境配置。
- `QishuiAccountService` 只接受认证网站用户ID，会话有归属、TTL、容量、创建/轮询节流和用户操作互斥。
- `QishuiCredentialStore` AES-256-GCM存储，AAD绑定用户与绑定代次，条件更新防旧刷新覆盖新绑定。多实例授权操作需要粘性路由。
- 新版解析只通过 `QishuiParseClient`，进入现有播放预算/并发/合并/缓存，不发往旧tf-pay，不跨源替换。加密音频明确不支持；不返回试听或加密文件假装完整播放。
- 未验证能力返回明确501。前端沿用统一音乐DTO；授权接口使用独立脱敏DTO，no-store。

## 实施与验证

已按行为测试驱动完成存储、客户端、授权会话、路由与部署配置；合成测试不包含本机账号或应用标识值。实际请求已验证曲库、二维码创建及已有账号个人资料。

完整新扫码/刷新/播放仍需账号持有人及匹配的上游凭据进行端到端验证。提交前运行针对性测试、完整 Maven clean verify，审查密钥/凭据未进入Git。推送不等于部署，本任务不修改生产运行状态。
