# DuckMusic Tonkiang 电视直播设计

## 目标

在 DuckMusic 中完整加入电视直播功能，直播线路唯一从 `https://tonkiang.us/?` 检索。第一阶段只做直播，不继续实现或展示点播、TVBox 仓库和 ncat21 功能。

验收范围包括：

- 按分类浏览常用频道。
- 按频道名称搜索。
- 查看并切换同一频道的多条直播线路。
- 使用 DuckMusic 原生播放器播放 HLS 直播。
- 在已支持的平台使用现有原生投屏桥接投屏。
- 播放失败时自动尝试下一条线路，并提供手动重试。
- Spring Boot 后端部署到现有 ECS，不引入 Node.js 或 Cloudflare Worker。

## 范围边界

第一阶段不包含：

- 影视点播目录、详情、选集或播放。
- TVBox JSON、JAR、JavaScript Provider 或配置仓库切换。
- 直播回看、录制、预约和节目单。
- 从其他第三方 M3U 仓库补充直播线路。
- 对上游频道或媒体内容作永久存储。

现有点播代码不在本次工作中做大规模删除。DuckMusic 导航中的“影视”入口改为“电视直播”，只进入新的直播页面；旧点播页面不再暴露给用户。后端旧点播 API 保持兼容但不参与直播调用，避免本次改动破坏已有未提交工作。

## 方案选择

采用“ECS 查询 Tonkiang + 受控 HTTPS HLS 中继”。

不采用客户端直接抓取 Tonkiang，因为移动端会遇到跨域、iOS ATS、页面结构解析和反滥用限制，且现有投屏服务拒绝 HTTP 媒体地址。

不采用“ECS 只返回原始线路”，因为 Tonkiang 当前结果大量为 HTTP HLS。直接返回会导致不同平台播放行为不一致，也不能满足投屏要求。

## 系统结构

直播功能与现有点播模型隔离，使用独立的 controller、service、DTO、Flutter model 和页面。

### ECS 后端组件

`LiveTvController`

- 暴露统一直播 API。
- 复用现有 Bearer 登录鉴权。
- 对关键词、频道 ID、分页和线路 ID 做边界校验。

`LiveChannelCatalog`

- 维护内置频道目录，不保存媒体地址。
- 初始分类为：推荐、央视、卫视、地方、体育。
- 每个频道包含稳定 ID、显示名称、检索关键词、分类和可选台标。
- 推荐分类包含常用央视和卫视频道；地方与体育允许先通过搜索发现，目录可以后续配置扩展。

`TonkiangLiveClient`

- 使用固定上游 `https://tonkiang.us/`，不接受客户端传入上游 URL。
- 按站点当前协议向 `/?` 提交 `seerch` 表单参数，并支持结果分页。
- 使用 Jsoup 解析 HTML，禁止用正则整体解析页面。
- 提取频道名、媒体 URL、检测日期、地区、分辨率和页面标注的速度信息。
- 页面结构变化、验证码、限流和上游不可用统一转换为稳定业务错误。

`LiveLineService`

- 对搜索结果做标准化、去重和排序。
- 只接受 HLS `m3u8` 线路；第一阶段不支持 RTSP、RTMP、FLV 和任意网页播放器地址。
- 候选线路按“探测可用、频道名匹配、HTTPS 优先、分辨率、上游速度标记”排序。
- 每次搜索最多探测有限数量的候选线路，避免批量抓取 Tonkiang。
- 搜索结果短期缓存；缓存键使用标准化关键词和页码。

`LiveHlsProbe`

- 对候选线路执行有超时、有大小上限的 HLS manifest 请求。
- 验证响应状态、内容类型或 `#EXTM3U` 内容，并识别 master/media playlist。
- 不下载完整媒体段，不通过探测接口向客户端暴露任意 URL 请求能力。

`LiveRelayService`

- 为可用线路签发短时 HTTPS 播放 URL。
- 获取并重写 HLS master/media playlist、嵌套 playlist、segment、key 和 map URI。
- 相对 URI 按原 manifest 地址解析；客户端始终只看到 ECS HTTPS 地址。
- 支持播放器需要的 `Range` 请求和合理的缓存响应头。
- 直播字节流不落盘、不进入数据库。

### DuckMusic 组件

`LiveTvApi`

- 调用直播分类、频道、搜索、线路和播放接口。
- 沿用现有 `ApiClient` 的认证、错误外壳和刷新令牌行为。

直播模型

- `LiveCategory`：分类 ID 和名称。
- `LiveChannel`：稳定 ID、名称、分类、台标和当前可用状态。
- `LiveLine`：线路 ID、分辨率、地区、检测时间、协议和可用性。
- `LivePlayback`：频道、线路、短时 HTTPS 播放 URL、格式和过期时间。

`LiveTvView`

- 顶部提供频道搜索。
- 使用分类切换央视、卫视、地方和体育。
- 频道列表为可扫描的紧凑布局，不复用点播海报卡片。
- 清楚区分加载、无结果、上游不可用和所有线路失效状态。

`LivePlayerView`

- 使用现有 `video_player` 播放 HLS。
- 显示频道名、当前线路、播放状态和线路切换菜单。
- 当前线路初始化或播放失败后自动尝试下一条可用线路，每条线路只自动尝试一次。
- 所有线路失败后停止重试，并显示“重新搜索线路”操作。
- 投屏继续复用 `CastService`，只传递 ECS 签发的 HTTPS 播放 URL。

## API 设计

所有目录和解析 API 使用 `/api/v1/live/**`，并要求登录。

### `GET /api/v1/live/categories`

返回内置直播分类。

### `GET /api/v1/live/channels?category=<id>`

返回指定分类的内置频道目录。频道对象不包含原始媒体地址。

### `GET /api/v1/live/search?keyword=<name>&page=1`

查询 Tonkiang，返回搜索标识、标准化关键词、分页信息和已探测的线路列表。关键词去除首尾空格后长度为 1 到 40 个字符，页码设置上限。

### `GET /api/v1/live/channels/{channelId}/lines`

使用目录频道的固定检索关键词查询 Tonkiang，返回已去重、排序和探测的线路。自定义关键词搜索已经在搜索响应中包含线路，不需要再次调用此接口。客户端在任何接口中都不能提交媒体 URL。

### `POST /api/v1/live/play`

请求体包含服务器签发的频道/搜索标识和线路 ID。搜索标识与线路 ID 均为服务端不透明值；后端再次确认线路仍属于当前缓存结果并签发短时播放 URL，不接受任意外部 URL。

### `GET /api/v1/live/relay/{token}/manifest.m3u8`

返回重写后的 HLS manifest。token 包含资源引用、资源类型、用户/会话约束和过期时间，并使用服务端 HMAC 签名。

### `GET /api/v1/live/relay/{token}/resource`

中继嵌套 playlist、segment、key 或 map。投屏设备无法携带 DuckMusic Bearer Token，因此 relay 使用短时签名 URL 鉴权；签名 URL 不赋予访问其他后端 API 的权限。

## 数据流

1. DuckMusic 加载内置分类和频道目录。
2. 用户点击频道或输入关键词搜索。
3. ECS 查询短期缓存；未命中时向 Tonkiang 发起一次受限查询。
4. 后端解析结果，对有限候选线路并发执行轻量 HLS 探测。
5. DuckMusic 展示可用线路；默认选择排序第一的线路。
6. 用户播放时，ECS 签发短时 HTTPS relay URL。
7. DuckMusic 或投屏设备请求 ECS manifest；ECS 获取上游 manifest 并重写其中资源 URL。
8. 播放器通过签名资源 URL 获取媒体段；失败时客户端切换下一条线路。

## 缓存与限流

- Tonkiang 搜索成功结果缓存 5 分钟，空结果缓存 1 分钟。
- 单个用户对新关键词查询做滑动窗口限流；缓存命中不计入严格上游额度。
- 相同关键词的并发请求合并，避免缓存击穿。
- manifest 使用短缓存，媒体段按 URL 和 Range 使用有界内存/反向代理缓存；不得无界占用 JVM 堆。
- relay token 默认有效 20 分钟，播放期间允许客户端刷新播放凭据。
- 对每用户和全局 relay 并发数、响应字节和上游超时设硬限制。

## 安全边界

直播中继不能成为通用代理。

- 上游媒体 URL 只能来自本次 Tonkiang 查询结果和服务器缓存，客户端不能直接提交 URL。
- 只允许 `http` 和 `https`，禁止 URL 用户凭据、非标准危险协议和过长 URL。
- 每次 DNS 解析和重定向都拒绝 loopback、link-local、private、site-local、multicast、unspecified 和云元数据地址。
- 禁止自动跟随到未重新校验的主机；限制重定向次数。
- manifest、响应头和单段大小设上限；限制嵌套深度和重写条目数。
- token 使用 HMAC-SHA256，包含过期时间和资源类型，使用常量时间比较。
- 日志不记录完整 token、查询参数中的上游凭据或媒体字节。
- Nginx 和 Spring Boot 同时限制连接数、带宽、超时与最大响应头。

## 错误处理

- Tonkiang 超时、页面变化或被限流：返回稳定的 `LIVE_UPSTREAM_UNAVAILABLE`。
- 未找到频道：返回成功的空结果，不作为系统错误。
- 候选存在但均无法探测：返回 `LIVE_NO_PLAYABLE_LINE` 和可重试提示。
- token 失效：返回 `LIVE_TOKEN_EXPIRED`；DuckMusic 重新调用 play 获取地址。
- relay 上游失败：返回 502；DuckMusic 自动尝试下一条线路。
- 用户退出页面或切换频道时取消未完成的搜索、探测和播放器初始化。

页面不显示堆栈、原始上游 HTML、内部主机地址或安全校验细节。

## 测试设计

### 后端自动化测试

- 使用保存的 Tonkiang HTML fixture 覆盖搜索结果、分页、空结果和页面结构异常。
- 测试频道名、URL、地区、检测日期、分辨率解析和线路去重排序。
- 使用本地 MockWebServer 覆盖 master playlist、media playlist、相对 URI、绝对 URI、key、map、Range 和重定向。
- 覆盖所有 SSRF 禁止地址、DNS 重绑定防护、签名篡改、过期 token、大小上限和嵌套深度。
- 覆盖缓存、请求合并、用户限流、探测超时和自动降级。
- 完整运行 Maven 测试，不允许破坏音乐、认证、会员和现有点播兼容测试。

### Flutter 自动化测试

- API JSON 解析和错误映射测试。
- 分类、搜索、空状态、错误状态、线路菜单和重试的 widget 测试。
- 播放线路自动切换状态机测试，确保无无限重试。
- 投屏只接受 ECS HTTPS URL，并保持当前平台不支持时的既有提示。
- 完整运行 `flutter analyze` 和 `flutter test`。

### 真实环境验收

- 从实际 Tonkiang 搜索至少一个央视和一个卫视频道。
- 每个验收频道至少确认一条线路能加载有效 HLS manifest，并连续播放 60 秒。
- 验证一条原始 HTTP HLS 经 ECS HTTPS relay 后可播放。
- 验证 HLS master、segment 和 Range 请求在生产域名下返回正确状态。
- 在支持的 Apple 平台验证 AirPlay 设备收到 HTTPS 地址并开始播放；其他平台验证原生投屏桥接的既有支持/不支持行为。
- 模拟首选线路失败，确认 DuckMusic 自动切换且页面不死循环。
- 部署到 ECS 后执行健康检查、登录 API、直播搜索、播放凭据和 relay 冒烟测试。

真实线路会随上游变化，因此自动化测试以固定 fixture 和 MockWebServer 为稳定基线，生产冒烟测试用于证明当前线路可用，但不把某条第三方线路永久写死在测试中。

## 部署与回滚

- 后端仍使用现有 Spring Boot ECS 发布流程，新增 relay HMAC 密钥、并发限制、带宽限制和缓存配置。
- Nginx 为 `/api/v1/live/relay/` 配置适合 HLS 的超时、Range 转发和关闭响应缓冲，保留全局限流。
- DuckMusic 使用现有发布配置指向 ECS API 域名，不直接请求 Tonkiang。
- 部署前备份当前可运行 JAR 和 Nginx 配置；生产冒烟失败时回滚 JAR/Nginx，并在客户端隐藏直播入口。

## 完成标准

只有同时满足以下条件才视为完成：

- DuckMusic 对用户只展示电视直播，不展示未完成的点播功能。
- 分类、搜索、线路筛选、播放、自动换线和投屏入口均可操作。
- Tonkiang 页面变化和线路失效有明确错误与重试路径。
- HTTP 上游通过受限 HTTPS relay 播放，relay 通过 SSRF 和签名安全测试。
- 后端和 Flutter 全量测试通过。
- 至少一个真实频道完成 60 秒播放验收。
- Spring Boot 后端成功部署到 ECS，生产 API 和 relay 冒烟测试通过。
