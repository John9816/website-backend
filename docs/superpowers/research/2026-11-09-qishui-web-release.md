# 黑源网页接入发布记录

时间：2026-11-09，Asia/Shanghai。优先完成用户要求的黑源网页接入；后端模块化继续在后续提交中进行。

- 后端：`b1a09109563e60a72a60bc8b99364ff57c0e6a96`（实现提交 `a5c9d3c`）。
- 前端：`d0bfbcf5eb9c355efadc3512647cc011974e19f8`。
- 发布目录：`/opt/website/releases/20261108T173544Z-qishui-b1a09109-d0bfbcf5`。
- 上一版本：`/opt/website/releases/20261108T092932Z-music-reliability-21728239f456`。
- JAR SHA-256：`61eef2f49bca32439a3cd3d7cb39f1862fd0184bcb4f087c6d5c3f2f25da3b5c`。
- 发布包 SHA-256：`92b3e82e7add6ef714a09645cb325567abd3e7daa79cbab65fd80be57b75cea2`。

实际生产数据库为 `website_java`，从现有 JDBC 配置解析，已备份并验证 gzip 完整性：`/opt/backups/website_java-before-qishui-20261108T173406Z.sql.gz`（432581字节）。新增 `user_qishui_account` 表，应用环境文件备份后安装 OAuth 应用参数及随机存储密钥；未迁移本机个人授权。生产未配置 TuneFree v1 播放凭据。

发布使用互斥锁、旧版本前置检查、上传包及所有文件 SHA-256 校验、原子软链接切换及失败回滚。systemd 文件与线上完全一致，保留现有附加配置、Nginx、下载镜像和持久化目录。前端原有未提交的下载部署文件没有纳入本次提交或运行包；发布元数据区分工作区 dirty 与运行源文件 clean。

验证：

- 后端 `clean verify`：443项通过；前端类型检查、构建、15项服务器测试、3项来源测试通过。
- 本地及线上构建的桌面/手机浏览器合成测试通过：歌曲搜索、复合歌单ID、扫码状态机、刷新、解绑、关闭取消、切换账号隔离和加密音频提示。
- 真实线上接口通过两个临时、无密码登录能力的专用测试账号验证，短期签名只保留在服务器内存中；测试后删除账号。
- 未登录搜索401；黑源搜索返回3条，冷查询1734ms，缓存查询18ms；歌词存在。
- 真正创建PNG二维码，轮询状态为 `new`；另一用户读取404；解绑后旧会话404；公开响应不含个人凭据且no-store。
- 未配置解析凭据时明确1003；未完成真人扫码确认、真实账号刷新及完整播放。
- 公网 API `/health`、网页 `/healthz` 和同源代理均正常。

临时验证日志：`/tmp/qishui-production-smoke.json`、`/tmp/website-qishui-production-browser.log`、`/tmp/website-qishui-production-qa/`。不提交凭据、二维码或个人账号材料。
