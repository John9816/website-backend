# DuckMusic TVBox 源切换设计

## 目标

将影视目录从固定的 ncat21 HTML 抓取改为可切换的 TVBox 兼容源。ECS 负责登录保护、源目录、Provider 执行和结果归一化；DuckMusic Flutter 只请求 ECS 的统一影视 API，不执行第三方 JAR/JS。

首版需要覆盖：

- 源列表展示、启用/禁用、默认源和客户端切换
- TVBox 配置 JSON 的下载和解析
- 标准 CMS JSON API 的分类、筛选、搜索、详情、选集和播放地址
- 当前源失败时的可见错误、重新加载和手动切换
- 现有视频历史、`video_player`、AirPlay/投屏链路复用

## 非目标

- 在 Spring Boot 主进程或 Flutter 中执行第三方 `spider` JAR/JS
- 在普通 JVM ECS 上直接加载 Android `classes.dex` Provider
- ECS 代理播放流或把任意外部 URL 转发给客户端
- 绕过第三方站点的验证码、登录态或私有签名

## 架构

### ECS 源目录和 Provider 执行层

影视仓配置由 ECS 配置文件维护，启动时校验并加载。每个仓库配置至少包含：

- 稳定 id、显示名称、配置 URL
- 格式（`TVBOX_JSON`、`M3U`）
- 执行能力（`CMS_HTTP`、`TVBOX_JS`、`DRPY_JS`、`DRPY_PY`、`ANDROID_DEX`）
- 启用状态、排序、默认标记
- 是否允许明文 HTTP（默认 false，仅管理员显式开启）
- 仓库内站点 key 和显示名称

公开接口只返回启用源的安全字段：

```text
GET /api/v1/videos/sources
Authorization: Bearer <token>
```

运维通过环境文件或发布配置完成源的增删改、启用/禁用、排序和默认源设置。源 URL 保存前校验 HTTPS、禁止凭据、禁止 localhost/内网地址和明显的重定向目标；HTTP 源只有在显式开启后才会返回给客户端。ncat21 保留为可选源，但不作为默认源。

默认种子源使用已实测返回 JSON 的教程地址：

- `https://16409.kstore.vip/tv/ngzmods.json`
- `https://9280.kstore.vip/newwex.json`

源目录配置在 ECS 维护，Provider 直接由 Spring Boot 进程内的纯 Java 适配器执行。Spring Boot 只接受已登记的 source id 和站点 key，不接受客户端提交任意脚本或任意 Provider URL。第三方配置按 source id 做短期缓存。

这两个教程配置经过探测后都以 `type=3` Android Provider 为主；`ngzmods` 的全局 `danmu.jar` 和 `qie.jar` 仅含 `classes.dex`，`newwex` 的全局 spider 甚至是图片。这些站点在源目录中明确显示不支持。`ngzmods` 的“剧和短剧”由 Java 原生适配器直连其已登记的 HTTPS 上游，作为首个可用教程源；标准 CMS 也由 Java HTTP 适配器执行。

公开源目录按仓库内站点展开，每个条目的 id 为稳定的 `仓库 id::站点 key`，并返回仓库名称、站点名称、能力和原因。客户端选择的是可执行站点，不会因为同一仓库中其他 Android-only 站点不可用而失效。

### Spring Boot 统一 API 与 Flutter 适配器

DuckMusic 从 ECS 获取源目录后，仅把 source id、站点 key 和用户操作发送给 Spring Boot。Spring Boot 负责下载配置、调用 Java Provider、把上游返回结构映射成稳定的 `VideoCategoryView`、`VideoSearchPageView`、`VideoDetailView` 和 `VideoPlayView`。客户端不执行远程代码。

统一 API 支持以下操作：

- 分类：读取 `class`/分类数组
- 发现和筛选：把分类、年份、地区、类型、排序映射到 CMS 查询参数
- 搜索：调用源的搜索接口并合并标准 `list`/`data` 项
- 详情和选集：读取 `detail`/`vod` 字段和播放列表
- 播放：Java Provider 只将 HTTPS 的 MP4、HLS 或 DASH 地址交给播放器；需要 Android Provider 的结果返回明确的能力状态

只提供 Android `classes.dex`、无法验证的 JAR 或专用加密协议的条目显示为“不支持此 Provider 能力”，不会让整个源失败。客户端不执行远程代码。

源选择保存在本地设置中，键名包含源 id；源被删除或禁用时自动回退到 ECS 返回的默认源。每个源的配置和目录缓存隔离，切换源会取消旧请求，避免旧结果覆盖新源。

### 影视页面与播放

扩展现有 `VideoApi` 为源感知 API，模型增加 `sourceId`/`sourceName`，但保留现有 `VideoItem`、`VideoDetail`、`VideoPlayback` 外形，降低页面改造量。影视页新增源切换控件，当前源失败时显示源名和失败原因，并提供切换/重试操作。

视频历史的 key 从 `ncat21 + videoId` 扩展为 `sourceId + videoId`，避免不同源的同 id 内容互相覆盖。播放器和投屏继续消费解析后的 HTTPS 媒体地址；无法得到受支持直链时，显示“该源需要外部播放器/暂不支持”，不把任意 URL 交给 `video_player`。

## 安全与故障处理

- 源配置 URL 和 CMS API URL 默认要求 HTTPS；HTTP 只作为显式的、可见风险源。
- 客户端拒绝 localhost、私网 IP、非 HTTP(S) scheme、包含用户信息的 URL 和跨源重定向。
- 第三方配置下载、解析和请求均有连接/读取/总时限；响应大小限制，JSON 解析失败只影响当前源。
- 不把媒体字节经过 ECS；播放地址只做格式和 scheme 校验。
- Java Provider 禁止执行客户端提交的脚本或访问客户端提交的 URL，并拒绝本机回环/内网管理地址。
- 源失败不会回退到另一个源的结果，避免用户看到错误内容；页面给出源状态和切换入口。
- 源配置缓存过期或无法下载时使用最后一次成功的本地副本，并明确标记缓存状态。

## 测试与验收

后端：

- 配置目录、公开列表、URL 校验和权限测试
- Java Provider 超时、能力标识、CMS 和短剧结果映射测试
- HTTPS、HTTP 显式开关、内网地址和非法 scheme 测试
- 默认种子源和禁用/默认回退测试

Flutter：

- TVBox 配置 fixture 解析（标准 `sites`、缺字段、仅 spider）
- CMS 分类、筛选、搜索、详情、选集和播放响应 fixture
- 源切换、缓存回退、禁用源回退和请求取消测试
- 历史按源隔离、播放器只接受 HTTPS MP4/HLS/DASH 的回归测试

验收要求：不依赖 ncat21；在模拟上游 fixture 下所有影视流程通过；至少一个 HTTPS 可执行教程源能在实际客户端完成源加载、搜索、详情和可播放地址解析；Android-only 源明确显示能力限制；源不可达时页面能切换到另一个源并给出清晰状态。

## 发布顺序

1. 先部署 Spring Boot 源目录、Java Provider 和统一 API。
2. 在模拟源上验证 CMS 与短剧 Provider 的分类、搜索、详情和播放映射。
3. DuckMusic 发布客户端源切换、播放和投屏能力。
4. 用一个有效登录账号验证至少一个可执行 Provider 的完整流程，并确认 Android-only 源被明确标注。
5. 确认新客户端覆盖后，再将 ncat21 从默认源中移除或禁用。
