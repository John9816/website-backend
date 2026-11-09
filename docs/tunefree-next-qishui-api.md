# TuneFreeNext 黑源（汽水音乐）接口与后端接入

调查日期：2026-11-08 至 2026-11-09，Asia/Shanghai。对象是本机 TuneFreeNext **4.3.5（9）**。本文是指定版本的实测与安装包分析结果，不是厂商公开承诺的稳定 API。

## 1. 结论与可用范围

黑源内部代码为 `qs`，对应汽水音乐。本站统一输出 `source=qishui`，请求兼容 `qs`。用户个人授权确实通过单独扫码获得，但它与曲库查询、TuneFree 播放解析是三种不同凭据：

| 链路 | 凭据 | 本次结果 |
| --- | --- | --- |
| 曲库、搜索、歌单、专辑、发现页、漫游请求 | Luna `client_token` | 发现页实测12首，漫游场景45个；个人 feed 使用绑定设备和 `common_params.user_id`，不是个人 ticket |
| 扫码登录、个人资料、绑定有效性 | 个人 `access_code`、ticket、设备 EC 私钥、Guard 证书 | 已恢复流程并验证个人资料签名；远端已修复真人扫码暴露的 Guard 头取票问题；本轮未重新扫码或刷新 |
| TuneFree v1 播放解析 | TuneFree v1 Bearer access token | 已拉取远端自动登录、CENC 解密和本地音频流实现；本轮未重测端到端播放 |

安装包说明个人登录信息用于音乐漫游，播放仍沿用原解析与会员规则。**不能用个人 `access_code` 当作曲库 token，也不能用旧版 UMS/tf-pay token 当作 TuneFree v1 Bearer。**

当前能力包括歌曲/歌单/歌手搜索、歌曲结果聚合的专辑搜索、歌单详情/按 ID 导入、分类/推荐歌单、专辑详情、歌手歌曲/专辑/简要资料、LRC + YRC、个人扫码绑定、发现推荐及有状态音乐漫游。第6节区分直接上游接口与搜索派生能力；歌单第二页现在会传递正确的首游标。

**榜单边界：**所检查的 4.3.5 发现页代码中，榜单集合为空，尚未恢复汽水官方排行榜协议。本站现有榜单与新歌端点返回标明“搜索发现／非官方榜单”的兼容结果，不能视为官方排名。分类歌单也是分类词搜索，不存在已验证的分类 ID 树。

远端现有播放分支已支持服务端解密 CENC 并通过带签名的同源音频流地址输出。客户端不接触上游票据或解密材料；取得本站播放 URL 只代表签发了流票据，实际解析、下载、解密发生在读取音频流时，不能据此宣称完整播放已验证。

## 2. 证据登记

| 项目 | 值 |
| --- | --- |
| 安装应用 | `/Applications/TuneFreeNext.app` |
| Bundle ID | `com.sayqz.tunefreenext.app` |
| 技术形态 | Flutter ARM64 AOT，非 Electron |
| AOT 文件 | `Contents/Frameworks/App.framework/Versions/A/App` |
| SHA-256 | `1155e63d8f759bddbf4fa5fee8cb3620f0af3902d8704cabcf2dd9b4b5411585` |
| REA Mach-O Evidence ID | `ev_86299e3e2454a812d981c2419952047e6dba7428455ed6b0f1fb3ac18faee452` |

REA 用于文件身份和布局确认。以下方法恢复来自独立的 LLDB 离线反汇编、Dart 运行时函数/常量池映射，不是 REA 反编译结果；运行时字符串也不等于网络抓包。

| 方法 | App 虚拟地址 | 证明的行为 |
| --- | --- | --- |
| `QsAccount.startLogin` | `0x31ef38` | 创建 QR 会话 |
| `QsAccount._qrParams` | `0x106be0` | native OAuth 参数 |
| `QsAccount.poll` | `0xdb7ec` | QR 状态 → auth code → access code → ticket → me |
| `QsAccount._exchangeTicket` | `0x104cd0` | 表单换票、CSR、响应证书 |
| `QsAccount._expiry` | `0x10562c` | access code 到期时间的三种形式 |
| `QsAccount._me` | `0xdd610` | POST 个人资料和 Guard 头 |
| `QsAccount._refresh` | `0x31ff04` | 刷新 access code、重新换票/读取资料 |
| `QsGuard.headers / sign / csr` | `0xddd3c / 0xddfbc / 0x1057f4` | 签名与 CSR |
| `QsClient._loadToken / commonParams / post` | `0x1c66a8 / 0x106498 / 0xa063d4` | 曲库 token 与通用请求 |
| `QsCatalogSource._search / getLyrics` | `0xa06780 / 0xa051e4` | 搜索/媒体元数据 |
| `QsCatalogSource.getPlaylistDetailPage` | `0xa15624` | 歌单 ID、owner、type 与分页 |
| `QsCatalogSource.getArtistDetailPage` | `0xa0fac0` | 歌手歌曲 |
| `QsCatalogSource.getDiscoveryFeed` / track closure | `0xa078c0 / 0xa07b9c` | `/feed/song-tab/`，匿名12首；热门歌单走搜索；榜单集合为空 |
| `QsCatalogSource.getCategoryPlaylists` | `0xa0fa7c` | 分类字符串直接转歌单搜索 |
| `QsPersonalFeedSession.songs / _post / _songTabBody` | `0x3e6d18 / 0x3e7cbc / 0x3e801c` | client token、绑定设备、个人 user_id、20首与模式计数 |
| `QsPersonalFeedSession.modes / QsFeedMode.parse` | `0x3e9308 / 0x3e936c` | 模式接口及 `feed_mode_block` 层级 |
| `QsFeedPage.parse / MusicRoamingController._fetch` | `0x3e6d94 / 0x3e69a8` | track 卡片、has_more、最多3批补取及最近1000首去重 |
| `ParseRepository.resolve / _decodePlayable` | `0xa1d3ac / 0xa1dff8` | v1 parse 请求、加密字段 |

临时原始证据在 `/tmp/tunefreenext-black-20261108/`，不会提交到 Git。含 `private` 的文件只用于本机验证，包含令牌、个人资料或带签名 URL。仓库内测试数据均为合成值。

真实 HTTP 结果（业务码 `0` 表示本次调用成功）：

| 操作 | 结果 | 本地证据名 |
| --- | --- | --- |
| v1 解析来源声明 | `success=true`，包含 qs | `parse-sources.json` |
| native QR 创建 | `data.error_code=0` | `qs-qr-verified-private.json` |
| client token | `err_no=0` | `qs-clienttoken-verified-private.json` |
| 歌曲搜索 | `err_no=0`，20 条、next_cursor=20 | `qs-search-verified-private.json` |
| 歌单搜索 | `err_no=0`，playlists 分组 | `qs-playlists-verified-private.json` |
| 媒体/歌词 | `err_no=0`，track_entity | `qs-media-verified-private.json` |
| 歌单详情 | `err_no=0`，count=3 返回3条，next_cursor=3 | `qs-playlist-detail-verified-private.json` |
| 歌手歌曲 | `err_no=0`，tracks 数组 | `qs-artist-tracks-verified-private.json` |
| 个人资料 | `err_no=0`，my_info | `qs-me-verified-private.json` |
| 封面 | 补齐模板后缀后 HTTP 200、image/png | 本次 HEAD 探测 |
| 匿名发现推荐 | `err_no=0`，12条，has_more=true | `qs-discovery-verified-private.json` |
| 漫游模式 | `err_no=0`，45个场景 | `qs-modes-verified-private.json` |
| 个人模式 / 默认 feed | `err_no=0`，45个场景 / 20条歌曲 | `qs-personal-modes-verified-private.json` / `qs-personal-feed-verified-private.json` |
| 场景模式连续两次 feed | 各20条，has_more=true | `qs-scene-feed-page1-verified-private.json` / `qs-scene-feed-page2-verified-private.json` |

本轮最终 Java 生产客户端复验：发现推荐12首、45个场景（响应另加1个本地默认模式）、模式图标 HTTP 200。此时本地个人凭据已过期，Java 探测跳过个人请求；表中个人 feed 为此前独立 HTTP 验证，不代表本轮重新确认了授权有效性或个性化排序质量。

早期尝试 `grant_type=client_credential` 获取 token 失败；将 `accessCode` 放入搜索的 `access-token` 也失败。这些失败不表示正确流程不可用，更不能据此宣称本机个人授权过期。

## 3. 上游曲库接口

### 3.1 通用参数

固定 User-Agent：`LunaOpenSDK/1.0.10`。平台请求 URL 是：

```text
https://open.douyin.com/api/luna/v1/platform{path}
```

下列通用字段在 URL query 中，`deviceId` 为当前客户端独立生成的标识，个人资料和个人漫游请求使用该次用户绑定的设备标识：

```text
aid=765213                         app_id=765213
app_name=byte_autoservice_lite      version_code=100010
version_name=1.0.10                channel=bydSong
sdk_aid=753999                     caller_name=luna_open_sdk
sdk_version_code=100000100         sdk_version_name=0.0.1
sdk_channel=Test                  device_platform=android
vehicle_type=BYD                  device_brand=BYD
device_type=BYD                   resolution=1920x1080
os_version=13                     ac=wifi
device_id=<deviceId>              did=<deviceId>
iid=<deviceId>                    vehicle_id=qishui-<deviceId>
_timestamp=<Unix秒>               _nonce=<0到65535的随机整数文本>
```

这些值是目标版本的兼容参数，不代表必填集合或任意应用可使用的授权声明。

### 3.2 曲库 client token

```http
GET https://open.qishui.com/api/luna/v1/platform/client/token/get/?<通用参数>&trigger_type=cold_start
User-Agent: LunaOpenSDK/1.0.10
```

成功字段在根对象：`client_token:string`、`expires_time:number`、`err_no:0`、`err_msg:""`、`now:number`。后端仅在内存缓存，最长一小时，且不越过上游到期时间减30秒。并发获取合并；上游拒绝 token 后使缓存失效，下一请求重新获取。

### 3.3 曲库 POST

所有下面的平台请求使用：

```http
POST <平台地址><path>?<通用参数>
Content-Type: application/json; charset=utf-8
User-Agent: LunaOpenSDK/1.0.10
access-token: <client_token>
```

普通目录及匿名 feed 的 JSON body 加 `"common_params": {}`；个人 feed 则为 `{"user_id":"绑定中的上游用户ID"}`。成功外壳为 `{ "err_no": 0, "err_msg": "", "data": {...} }`；非零业务码不能当作空结果缓存。

**搜索 `/search/`**：

```json
{
  "common_params": {},
  "q": "查询词",
  "cursor": "0",
  "search_id": "每次搜索生成的UUID",
  "from_search_id": "同一个UUID",
  "search_method": "input",
  "search_type": "track",
  "search_scene": "normal",
  "scene_name": "search",
  "ab_param": "",
  "last_viewed_media": {}
}
```

`search_type=track`、`playlist` 或 `artist`，结果在 `data.result_groups[]`；对应 group.id 为 `tracks`、`playlists` 或 `artists`。组内包含 `data[].entity.track/playlist/artist`、`has_more:boolean`、`next_cursor:string`。`ab_param` 必须是字符串，`last_viewed_media` 是对象。当前专辑搜索从最多100条歌曲命中中的 `related_info.album_link` 聚合去重，再作分页，不能当作独立的上游 album 搜索，也不承诺完整专辑总数。

本次 track 首页固定返回20条。适配器按目标版本用 `(page-1)*pageSize` 作为首个游标，再使用响应的 `next_cursor` 补齐页面，最多5次且受8秒总预算约束；检测重复游标，最后裁剪到 pageSize。不能假设上游支持客户端给定 count。

**媒体详情/歌词 `/media/mget/`**：

```json
{"common_params":{},"media_list":[{"id":"字符串歌曲ID","type":"track"}],"card_context":{}}
```

结果 `data.media_list[]` 包含 `media_id`、`media_type`、`track_entity`。适配器根据 `track_entity.base_info.id` 确认歌曲身份，不把第一条无条件当作目标歌曲。

**歌单详情 `/playlist/detail/`**：

```json
{"common_params":{},"playlist_id":"歌单ID","owner_id":"拥有者ID","type":2,"count":30,"cursor":"0"}
```

owner_id 和 type 来自搜索结果 `playlist.base_info`；不要用网站用户 ID 替换。结果包含 `playlist`、`media_entities[].track_entity`、`has_more`、`next_cursor`。本站歌单 ID 是 `playlistId:ownerId:type` 的不透明字符串；前端必须原样保留并 URL 编码，不能只截取数字部分。

**歌手歌曲 `/artist/tracks/`**：

```json
{"common_params":{},"artist_id":"歌手ID","count":30,"cursor":"0"}
```

结果包含 `tracks[]`、`has_more`、`next_cursor`；tracks 条目直接使用下表的 track 结构。歌手简要资料从歌曲的 `artist_links` 取对应 ID，歌曲总数/专辑数未知时保持 null。

**专辑详情 `/album/detail/`**：

```json
{"common_params":{},"album_id":"专辑ID","count":30,"cursor":"0"}
```

结果包含 `album_info` 与 `tracks[]`。专辑资料读取 `base_info.id/name/intro/count_tracks/release_date`、`display.url_cover`、`related_info.artists`。发布日期 epoch 秒转换为北京时间日期。此能力来自本轮拉取的远端实现及其回归测试，本轮未新增真实专辑网络探测。

**歌手专辑 `/artist/albums/`**：

```json
{"common_params":{},"artist_id":"歌手ID","count":30,"cursor":"0"}
```

结果 `albums[]` 使用上述专辑结构，本站保留歌手 ID 列表；上游未给定总数时 `total=null`。

### 3.4 发现页与音乐漫游

**推荐歌曲 `POST /feed/song-tab/`**，匿名发现页的请求体：

```json
{
  "common_params":{},
  "played_media":[],
  "is_first_request":true,
  "with_client_cache":false,
  "is_did_first_request":false,
  "count":12,
  "feed_preference":{
    "preference_mode":null,
    "total_request_count":1,
    "preference_mode_request_count":1,
    "current_mode_request_count":1,
    "scene_mode_id":null,
    "scene_mode_select_time":null
  }
}
```

个人漫游使用同一路径、同一 `client_token`，但通用 query 使用绑定设备，`common_params.user_id` 来自当前网站用户的加密绑定，`count=20`。不使用 ticket、Guard，不接受前端指定上游身份。

| 字段 | 已恢复的规则 |
| --- | --- |
| `total_request_count` | 会话从1开始，每次上游取歌递增 |
| `preference_mode_request_count` / `current_mode_request_count` | 当前模式从1开始，切换模式重置 |
| `is_first_request` / `with_client_cache` | 当前模式首次为 true/false，后续为 false/true |
| `is_did_first_request` | 会话首次为 false，后续为 true；字段名虽反直觉，按目标实现发送 |
| `scene_mode_id` | 默认模式为 null；场景模式为上游返回的数字 ID |
| `scene_mode_select_time` | 默认模式为 null；切换场景时的 Unix 秒，后续同场景沿用 |
| `played_media` / `preference_mode` | 目标版本保持 `[]` / null；本站不虚构已播放上报 |

歌曲在 `data.items[].entity.media.track_entity`；外层卡片 `type=track`，内层 `media_type=track`。响应还有布尔 `data.has_more`，没有 page cursor。本站过滤非歌曲卡片，批内及会话最近1000首按字符串 ID 去重。空批/全重复且仍有更多时，一次本站调用最多取3批，总预算8秒；仍无新歌返回1004，不能把“没有新歌”假装成上游结束。

**模式 `POST /feed/mode/`**：匿名 body `{"common_params":{}}`；个人 body 增加相同的绑定 user_id。响应路径：

```text
data.feed_mode_block[].title
data.feed_mode_block[].feed_mode[].entity.feed_mode
  type = "feed_scene_mode"
  text / cutover_toast / url_info
  scene_mode.scene_mode_id / scene_mode.sub_queue_type
```

后端从 `text`、块标题、`url_info`、`cutover_toast` 提供名称/分组/图标/提示。每次从接口读取模式集合，实测45个场景；另加本地 `recommended`（为你推荐）。前端把模式 ID 当字符串，不能写死场景清单。`sub_queue_type` 不属于当前本站控制参数。

**歌单发现与榜单**：目标 `getCategoryPlaylists` 调用分类词歌单搜索；目标发现页热门歌单为 `searchPlaylists("热门", limit:10)`。本站 `/playlist/recommend` 兼容实现按“热门、华语流行、新歌”搜索并按复合 ID 合并。`order` 仅沿用本站响应字段，上游没有已验证的分类排序参数。官方榜单没有恢复，不能构造 `/toplist/` 上游路径。

### 3.5 字段映射

| Luna track 字段 | 本站 SongSearchItem |
| --- | --- |
| `base_info.id` | `id`，始终字符串，避免19位数字精度丢失 |
| `base_info.name` | `name` |
| `base_info.duration_ms` | `durationMs`，`durationSec=durationMs/1000` |
| `related_info.artist_links[].id/name` | `artistIds`、首位 `artistId`、`artist` |
| `related_info.album_link.id/name` | `albumId`、`album` |
| `display_info.cover_url` | `coverUrl` |
| `display_info.lyric_info.lyric_entity` | 歌词信息 |

封面对象的 `urls[0]` 若只是以 `/` 结尾的基地址，则拼接 `uri`，有 `template_prefix` 时再拼接 `~<template_prefix>-image.image`。已是完整 URL 的保持原样。

KRC 歌词文本如 `[1000,800]<0,300,0>测<300,500,0>试` 转为：

```text
LRC: [00:01.000]测试
YRC: [1000,800](1000,300,0)测(1300,500,0)试
```

逐字时间采用行起点加相对偏移。目录里的 `quality_map` 是权益/品质描述，并不能证明解析器当时可播放；本站 `availableQualities` 暂不虚报，返回空数组。

## 4. 用户扫码授权

### 4.1 调用顺序

```text
每位网站用户独立创建会话及设备 EC 密钥
  → GET open.douyin.com/oauth/get_qrcode/
  → 用户使用抖音扫码并确认
  → GET open.douyin.com/oauth/check_qrcode/
  → confirmed + code/auth_code
  → GET open.qishui.com/api/luna/v1/platform/access/code/get/
  → POST open-client.douyin.com/passport/open/get_access_ticket/
  → POST open.douyin.com/api/luna/v1/platform/me/info/
  → 按当前网站 userId 加密保存
```

### 4.2 创建/轮询二维码

创建和轮询都使用这些 query 参数：

```text
aid=1128
client_key=<部署配置的应用client key>
scope=user_info,luna.openapi.platform.interaction,luna.openapi.platform.me_tab,luna.commerce.openapi_platform.vip_info
state=
next=
jump_type=native
optional_scope_check=
optional_scope_uncheck=
customize_params={"source":"pc_auth","not_skip_confirm":"true"}
source_from=native
device_platform=android
app_identity=<部署配置的应用标识>
signature=<部署配置的应用签名>
```

应用参数不是用户 ticket，不在仓库硬编码。创建的 `data` 包含 `error_code`、`qrcode`、`qrcode_index_url`、`token` 等。实测 `qrcode` 为 **PNG 的 base64**，`qrcode_index_url` 是 **aweme scheme 深链**，不是普通 HTTPS 图片地址。

轮询额外添加 `token=<该次创建结果>`、`timestamp=<Unix毫秒>`。状态有 `new`、`scanned`、`confirmed`、`refused`、`expired`。确认后优先读取 `code`，否则读取 `auth_code`。

### 4.3 access code 与换票

取得访问码：

```http
GET https://open.qishui.com/api/luna/v1/platform/access/code/get/?<通用参数>&auth_code=<用户确认后的code>
```

读取响应数据的 `access_code`、`open_id`（兼容 `open_uid`）、`expires_time`。`expires_time` 的目标代码处理规则：

- 大于 `500000000000`：Unix 毫秒；
- 大于 `1000000000`：Unix 秒；
- 否则：从现在开始的剩余秒数；
- 缺失：目标代码默认21600秒；后端拒绝非正数。

换票：

```http
POST https://open-client.douyin.com/passport/open/get_access_ticket/?aid=1128&client_key=<client_key>&device_platform=android
Content-Type: application/x-www-form-urlencoded
User-Agent: LunaOpenSDK/1.0.10
bd-ticket-guard-version: 1
bd-ticket-guard-client-csr: <base64(UTF8(PEM CSR))>

access_code=<access_code>&open_id=<open_id>
```

CSR 为 P-256 EC 公钥的 PKCS#10 请求，subject `CN=bd-ticket-guard`，签名算法 SHA256withECDSA。响应正文读取 `data.error_code`；响应头 `bd-ticket-guard-server-data` 是 base64 JSON，含 **`ticket`**、`client_cert` 与 `ts_sign`。`ticket` 不在正文 `data` 中；2026-11-09 的真人扫码联调暴露并修正了此前文档及实现对该字段位置的误判。证书若为 PEM，保存/发送前将整段 PEM UTF-8 base64 编码。到期时间取前一步 access code 响应，不能从 ticket 响应猜测。

### 4.4 个人资料与签名

```http
POST https://open.douyin.com/api/luna/v1/platform/me/info/?<该用户设备的通用参数>
Content-Type: application/json
access-token: <ticket>
bd-ticket-guard-version: 1
bd-ticket-guard-client-cert: <guardCert>
bd-ticket-guard-client-data: <base64(UTF8(JSON(clientData)))>

{"common_params":{}}
```

签名原文为 UTF-8：

```text
ticket=<ticket>&path=/api/luna/v1/platform/me/info/&timestamp=<Unix秒>
```

`clientData`：

```json
{"ts_sign":"服务器返回的ts_sign","req_content":"ticket,path,timestamp","req_sign":"base64(DER ECDSA签名)","timestamp":1794150000}
```

签名使用绑定设备的 P-256 私钥及 SHA-256，`req_sign` 为 DER 编码的 r/s 序列。已通过真实个人资料请求验证此协议。当前 headers 实现只用于上述 me/info 路径；不能直接复用于目录和漫游其它路径。

成功数据为 `data.my_info`，个人字段包括 `id`、`nickname`、`medium_avatar_url`、`is_vip`。公开 API 只返回昵称、头像、会员标志、绑定状态与到期时间。

刷新是 `GET /access/code/refresh/`（open.qishui.com，通用参数加旧 `access_code`），然后使用原设备私钥再次换票、读取资料。刷新返回缺失 open_id 时沿用原 openId。刷新调用链已有合成 HTTP 测试，尚未对真实账号执行，以免改变本机应用的授权状态。

## 5. TuneFree v1 播放

来源声明：`GET https://tunefree.sayqz.com/v1/parse/sources`，本次无需认证，qs 声明支持 `128k/320k/flac/flac24bit`，默认320k。

从 `ParseRepository.resolve` 和认证客户端恢复：

```http
POST https://tunefree.sayqz.com/v1/parse
Authorization: Bearer <TuneFree v1 access token>
Content-Type: application/json

{"source":"qs","trackId":"汽水歌曲ID","quality":"320k"}
```

解码读取 `success/data`、`playUrl`、`expiresAt`、`requestedQuality`、`actualQuality`，以及可选 `encryption`。目标安装包包含 `scheme=qishui-cenc`、`method=cenc-aes-ctr` 和 `spadeA` 分支。

当前远端实现（`e04b23f`、`d2e3c7b`、`85f6d90`）已替换早期“拒绝加密音频”的分支：

- `TuneFreeV1AuthService` 优先使用可选的 `QISHUI_PARSE_ACCESS_TOKEN`；否则用系统配置 `music.tunefree.account/password` 登录 `/v1/auth/login`，在内存维护 v1 access/refresh token，经 `/v1/auth/refresh` 刷新。与旧 tf-pay 的 token 空间隔离。
- `/api/v1/music/play` 仍需网站登录及有效会员，`prefetch=true` 保持不记历史。汽水默认320k，返回原曲 source/id 与 `lyricMatch=exact`。
- `playUrl` 默认形如 `/api/public/music/qishui/stream?ticket=<签名票据>`，票据有效1200秒，`PlayInfo.expireSec=1080`。该 URL 可直接交给同源 `<audio>`，不需要向音频请求加 Bearer。
- 流端点校验票据后才执行 TuneFree parse、下载、CENC AES-CTR 解密，返回 `audio/mp4`，支持 Range 的200/206及不满足范围的416。解密材料、上游签名 URL 不返回浏览器。
- 流下载使用独立60秒调用上限，并受48MiB音频大小检查约束；内存缓存上限64MiB，磁盘缓存上限1GiB，缓存TTL1100秒。`flac24bit` 与 `flac` 共用缓存键。
- 当前 `PlayInfo.actualQuality` 在签发票据时使用请求值，尚不能证明流最终达到该品质；不要把选择 Hi-Res 当作实际高解析音质的证据。此处如需准确呈现，应进一步传递最终解析品质。

音频票据签名含进程随机盐，重启会使旧票据失效，多实例也不能直接互验；客户端重新取播放地址，部署时让取地址与流请求命中同一实例。本轮仅核对该已有实现与自动化测试，没有再次运行完整音频播放。

## 6. 本站接口文档

以下接口均要求网站 Bearer 登录；用户归属只取服务端认证属性，不接受 body/query 的 userId。

| Method | Path | 请求/响应 |
| --- | --- | --- |
| POST | `/api/v1/music/qishui/auth/qr` | 无 body；创建当前用户 QR 会话 |
| GET | `/api/v1/music/qishui/auth/qr/{sessionId}` | 轮询并在确认后完成绑定 |
| GET | `/api/v1/music/qishui/account` | 当前用户的绑定状态及脱敏资料 |
| POST | `/api/v1/music/qishui/account/refresh` | 刷新当前用户授权 |
| DELETE | `/api/v1/music/qishui/account` | 删除本地绑定及待确认会话；不代表上游撤销授权 |
| GET | `/api/v1/music/qishui/discovery` | 无参数；发现推荐12首以内；只需网站登录，不需个人绑定 |
| GET | `/api/v1/music/qishui/roaming/modes` | 要求有效个人绑定；返回默认模式及上游场景数组 |
| POST | `/api/v1/music/qishui/roaming/sessions?modeId=recommended` | 可选 query `modeId`；创建会话并返回第一批歌曲 |
| POST | `/api/v1/music/qishui/roaming/sessions/{sessionId}/next?sequence=1&modeId=2` | 必填 query `sequence`；`modeId` 可省略以沿用当前模式 |
| DELETE | `/api/v1/music/qishui/roaming/sessions/{sessionId}` | 关闭本人漫游会话；data=null |

响应沿用 `{code:0,message:"ok",data:{...}}`，HTTP `Cache-Control: no-store`。创建示意：

```json
{"code":0,"message":"ok","data":{
  "bound":false,"status":"new","sessionId":"随机UUID",
  "qrcode":"PNG的base64","qrcodeUrl":"aweme://...",
  "sessionExpiresAt":"2030-01-01T00:03:00Z","pollIntervalSeconds":2
}}
```

前端将 `qrcode` 拼接为 `data:image/png;base64,<qrcode>` 显示，按2秒轮询。`qrcodeUrl` 是可选深链；不要当图片 src。每个网站用户只保留一个会话，新建会替换旧会话；每秒最多新建一次；单进程最多保留1000个会话，3分钟自动到期。重启或容量淘汰后重新创建。

轮询状态：上游五种状态加 `failed`（确认后交换失败，必须重新扫码）。终态重复轮询幂等。若只有数据库保存失败而换票成功，重试只保存内存中的结果，不重放已消费的授权 code。

账户状态为 `unbound`、`bound`、`expired`；`bound=true` 表示存在绑定，是否尚在有效期另看 status。账户响应 `expiresAt` 是授权期限；QR 响应 `sessionExpiresAt` 是二维码会话期限。昵称/头像/VIP 仅在有已保存资料时存在，不返回平台 userId、accessCode、openId、ticket、证书或私钥。

### 6.1 发现页和漫游调用示例

下列 POST 参数在 **query** 中，无 JSON body。每个请求携带本站 `Authorization: Bearer <token>`。

```http
GET /api/v1/music/qishui/discovery
GET /api/v1/music/qishui/roaming/modes
POST /api/v1/music/qishui/roaming/sessions?modeId=recommended
POST /api/v1/music/qishui/roaming/sessions/<sessionId>/next?sequence=1
POST /api/v1/music/qishui/roaming/sessions/<sessionId>/next?sequence=2&modeId=2
DELETE /api/v1/music/qishui/roaming/sessions/<sessionId>
```

发现页响应 `data={"source":"qishui","list":[SongSearchItem...]}`。模式响应示例（图标等值省略）：

```json
{"code":0,"message":"ok","data":[
  {"id":"recommended","name":"为你推荐","group":"推荐","iconUrl":null,"toast":"已切换到为你推荐"},
  {"id":"2","name":"运动","group":"场景","iconUrl":null,"toast":"已切换"}
]}
```

创建及续页的响应结构相同。以下 `list` 为示意；歌曲字段同3.5节的 `SongSearchItem`，没有播放 URL，播放仍调用 `/music/play`：

```json
{"code":0,"message":"ok","data":{
  "sessionId":"随机UUID",
  "source":"qishui",
  "modeId":"recommended",
  "sequence":0,
  "nextSequence":1,
  "hasMore":true,
  "expiresAt":"2030-01-01T00:30:00Z",
  "list":[{"id":"9000000000000000001","source":"qishui","name":"示例歌曲"}]
}}
```

- 创建返回 `sequence=0`；续页使用上次响应的 `nextSequence`，合法范围1至100000。一次最多返回20首，去重后可能更少。
- 最新一次已完成的续页序号可以重试，返回同一批歌曲且不重复请求上游。只保留最新一页的重试结果；旧序号、跳号、同序号改成另一模式返回 code=409。
- 续页省略 `modeId` 沿用当前模式；显式 `recommended` 回默认。上游成功返回的模式 ID 才能切换，未知 ID 返回400。
- 失败不会提交模式、序号、去重状态和计数变更；使用相同参数重试即可。失败时上游可能已处理 HTTP 请求，本地状态回滚不等于能撤销上游处理。
- `hasMore=false` 时停止自动续页。同模式继续请求不会再访问上游，返回空 list；用下个合法序号切换模式可以继续。
- 绑定有效性在每次调用、重试读取及上游返回后检查。刷新/重新绑定改变 bindingId，解绑或到期后拒绝旧会话；跨网站用户不可续用 sessionId。
- 每个网站用户只保留一个漫游会话，成功创建才替换旧会话；每秒最多创建一次。最多1000个会话，绝对期限为30分钟与个人授权期限的较早者，续页不延长。使用进程内会话及分条操作锁，冲突立即返回1013，不排队重复取歌。
- 所有新 feed 端点及其控制器错误响应带 `Cache-Control: no-store`。个人结果不进入全站 MusicCache；会话失效、进程重启或容量淘汰后重新创建。

### 6.2 完整目录能力与来源

音乐接口继续使用现有响应 DTO；未写全的分页参数均沿用现有端点：

| 请求 | qishui 支持情况 |
| --- | --- |
| `GET /api/v1/music/search?source=qs&keyword=...&type=song` | 直接 track 搜索；type=playlist/artist 也为直接上游搜索；pageSize最大30 |
| `GET /api/v1/music/search?source=qs&keyword=...&type=album` | 歌曲命中的专辑关联聚合；有限候选，不是完整专辑搜索 |
| `GET /api/v1/music/lyric?source=qs&id=...` | 支持；provider=qishui，official=true，KRC归一化为YRC |
| `GET /api/v1/music/playlist?source=qs&category=摇滚&page=2&pageSize=20` | 搜索分类词，默认“热门”；page=2初始cursor=20，最大pageSize=50；total未知 |
| `GET /api/v1/music/playlist/recommend?source=qs&limit=20` | 热门/华语流行/新歌的歌单搜索合并，limit最大50；不是个人推荐 |
| `GET /api/v1/music/playlist/detail?source=qs&id=<复合ID>` | 支持；pageSize最大100 |
| 现有用户歌单按 source + sourceId 导入 | 支持 qs 复合ID；未增加分享短链解析 |
| `GET /api/v1/music/toplist?source=qs` | 3个搜索发现分组，id分别为“热门”“华语流行”“新歌”；name/description明确标注非官方榜单 |
| `GET /api/v1/music/toplist/detail?source=qs&id=热门` | 以该词搜索歌曲；只接受上述3个ID；total未知 |
| `GET /api/v1/music/new?source=qs` | 同“新歌”搜索发现；不保证上架时间排序 |
| `GET /api/v1/music/album/detail?source=qs&id=...` | 直接 `/album/detail/`，专辑信息及分页歌曲 |
| `GET /api/v1/music/artist/works?source=qs&id=...&type=song` | 直接歌手歌曲；type=album 使用 `/artist/albums/`；未知total为null |
| `GET /api/v1/music/artist?source=qs&ids=...` | 尽力提供歌曲关联中的简要资料；无歌曲时可能缺项 |
| `GET /api/v1/music/play?source=qs&id=...&quality=320k` | 网站会员接口，签发本地音频流地址；v1登录/解析/CENC解密在读取流时执行 |

歌曲 ID 保持字符串；歌单 ID `playlistId:ownerId:type` 原样 URL 编码。modeId 也是字符串。榜单与分类的中文 query 必须 URL 编码，不拼成未经验证的上游地址。收藏/导入写入的是本站用户歌单，不是写回汽水收藏。

### 6.3 错误码

响应 `{code,message,data}`；**业务码409在本项目现有 GlobalExceptionHandler 中对应 HTTP 400**，前端需同时读取 JSON code，不能只等 HTTP 409。

| JSON code | HTTP | 含义与处理 |
| --- | --- | --- |
| 400 | 400 | sequence缺失/格式错误、非法模式等；修正参数 |
| 401 | 401 | 未登录网站 |
| 404 | 404 | 会话不存在、不属于本人、已关闭或绝对期限已到；重新创建 |
| 409 | 400 | 未绑定/授权过期/绑定改变，或序号不连续/重试模式冲突；检查账号与会话 |
| 429 | 429 | 创建会话过快，稍后重试 |
| 1003 | 502 | 缺少配置或上游凭据 |
| 1004 | 502 | 上游失败、响应格式不符、3批内未取到新歌；保留当前序号重试 |
| 1009 | 504 | 请求超时 |
| 1012 | 501 | 调用了不支持的操作 |
| 1013 | 503 | 操作锁冲突，稍后以同一序号重试 |

feed 错误不附上游原文、签名 URL、响应头或凭据。

## 7. 部署与隔离

执行数据库迁移：`src/main/resources/db/migrations/20261109_add_user_qishui_account.sql`。外键关联实际 `users(id)`；一个网站用户一条记录，删除用户时级联删除。

环境变量已加入 `application.yml` 与 `deploy/production/backend.env.example`：

| 变量 | 用途 |
| --- | --- |
| `QISHUI_ENCRYPTION_KEY` | 32字节随机密钥的base64；示例生成命令 `openssl rand -base64 32` |
| `QISHUI_CLIENT_KEY` | 自行配置的 OAuth 应用 client key |
| `QISHUI_APP_IDENTITY` | 匹配该应用的身份参数 |
| `QISHUI_APP_SIGNATURE` | 匹配该应用的签名参数 |
| `QISHUI_PARSE_ACCESS_TOKEN` | 可选，新版 TuneFree v1 解析凭据 |
| `QISHUI_PUBLIC_BASE_URL` | 默认空，返回同源音频相对路径；分离部署时可配置公开API基地址 |
| `QISHUI_CACHE_DIR` | 已有解密音频磁盘缓存目录，默认 `/opt/website/cache/qishui-audio` |

密钥固定保存于部署环境，所有副本一致；丢失/更换后旧密文不能解密，需要重新授权。参数为空不影响旧音乐源启动，只有相关功能调用会报配置错误。目录查询不依赖个人凭据或 OAuth 配置。

个人凭据 AES-256-GCM 加密，随机12字节IV，128位标签，AAD绑定网站 userId及随机bindingId，密文格式 `v1.base64(iv).base64(ciphertext+tag)`。每次绑定/刷新轮换bindingId；刷新采用数据库条件更新，旧刷新不能覆盖新绑定或复活已删除记录。重新绑定也检查开始登录时的绑定代次。

二维码、漫游会话及操作锁在内存中。多实例部署必须把同一用户的授权创建、轮询、刷新、解绑、漫游固定到同一实例；数据库CAS只保护写回，不能代替跨节点授权会话取消。若需要无粘性的水平扩容，应先引入共享会话和分布式互斥。漫游新增能力复用已有加密绑定表，无新数据库迁移。

公开目录结果使用全站 MusicCache；个人资料和漫游结果不进入该缓存。漫游会话按 `websiteUserId + bindingId` 校验归属，服务器自行读取上游 user_id。本次没有将本机已有授权导入网站数据库，也没有部署服务器。

## 8. 验证与限制

2026-11-09 本轮执行 `mvn -q clean verify`：**85个测试套件、500项测试，失败/错误/跳过均为0**。测试使用Java 21运行、按项目Java 8目标编译。完整测试日志保留在本机 `/tmp/qs-feed-clean-verify.log`；接口契约逐项比较确认既有路由、DTO及实体没有修改或删除。

自动化覆盖：既有授权加密/Guard/绑定代次、目录搜索/分页/歌词、远端加入的专辑/歌手/CENC/音频票据/缓存；本轮新增歌单第二页、真实 feed 卡片层级、绑定身份与catalog token、场景计数、未知模式、用户隔离、序号幂等、失败回滚、返回对象隔离、请求途中到期、账号过期重试、有限去重补取、并发冲突和控制器登录/错误/no-store。API合同新增5条路由、3个DTO，数据库实体仍34个。

早期接入阶段的 Java 客户端真实网络检查：歌曲搜索返回3条、取得歌词、歌单详情返回3条；当时已有个人授权的 Guard 签名请求返回个人资料；新二维码返回PNG，首次轮询为 `new`。Java 生成的 CSR 已通过 `openssl req -verify -noout` 验签。这些为此前的生产客户端检查，不代表本轮重新验证了账号授权。

真实网络验证与自动化测试分开记录：本轮 Java 已验证发现推荐与模式；此前独立 HTTP 已验证个人 feed 与场景续取。本轮 Java 个人探测因本地授权已过期而跳过，未重新扫码或刷新，未再做带会员权限的完整播放验证。自动化成功不补齐这些实测缺口。

用户最初给出的 mhub 项目（`https://github.com/jizizr/mhub`，参考提交 `8cda3d5dcd940677b86e060832baca36b2e25c8f` 的 `src/music/soda.rs`）另有匿名 `api.qishui.com/luna/search/track` 和 `beta-luna.douyin.com/luna/h5/seo_track` 路线。本次交叉探测成功，但所测 H5 播放器是试听；后端主适配没有偷偷切换到这条路线，没有搬入其下载/解密实现。
