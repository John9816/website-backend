# 黑源 App 联调修复发布

日期：2026-11-09，Asia/Shanghai。

App调用真实接口时发现黑源歌单搜索HTTP500：上游总数未知时返回null，MusicSearchService的三元表达式将Long拆箱。保留nullable Long，维持未知总数语义，修复缓存和响应流程。

- 源码：`66b7e09f59a8926eb90151df8966c3cbe4b49995`，已推送 `codex/tvbox-provider`。
- 回归测试先重现NullPointerException，再通过；`mvn -q clean verify`：77套件456项，失败/错误/跳过均为0。
- 发布目录：`/opt/website/releases/20261109T004202Z-qishui-app-66b7e09f-d0bfbcf5`。
- JAR SHA-256：`c0d4d5ffe02eed6741d0f0efa639b8e055a1e60383e854af7c6abe677601c000`。
- 发布包SHA-256：`a0026c4ce5bcde8c04111ce7fc9e96b91a9e16df2e1b3bd7955a695052aed8b9`。
- 前端复用上一版已校验的发布包，源提交`d0bfbcf5eb9c355efadc3512647cc011974e19f8`；本机网站工作区存在独立编辑器改动，本次未纳入。
- 发布锁、前置版本、包哈希、内部清单和systemd配置核对后激活，自动回滚保护沿用现有脚本；website-api/website-ecs均active，两个health接口成功。
- 上一发布：`/opt/website/releases/20261108T180346Z-modules-46aeee7e-d0bfbcf5`；保留原版本以便回退。

上线后真实公网验证：未登录拒绝、黑源歌曲和歌单搜索、复合歌单详情、歌手歌曲、歌词、二维码PNG、跨用户读取拒绝、本人轮询、解绑后会话取消均通过。测试创建的两个临时用户已删除。搜索冷请求1790ms、同查询缓存57ms，为单次观测。

QISHUI_PARSE_ACCESS_TOKEN仍未配置，播放返回1003；用户确认没有独立解析凭据。文档所述个人扫码access_code/ticket不能替代TuneFree v1播放Bearer。未声称完成真实新扫码确认/换票/刷新或完整音频播放。未修改生产密钥与用户授权配置。
