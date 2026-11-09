# TuneFreeNext 黑源（汽水音乐）接口与后端接入

调查日期：2026-11-08 至 2026-11-09，Asia/Shanghai。对象是本机 TuneFreeNext **4.3.5（9）**。本文是指定版本的实测与安装包分析结果，不是厂商公开承诺的稳定 API。

## 1. 结论与可用范围

黑源内部代码为 `qs`，对应汽水音乐。本站统一输出 `source=qishui`，请求兼容 `qs`。用户个人授权确实通过单独扫码获得，但它与曲库查询、TuneFree 播放解析是三种不同凭据：

| 链路 | 凭据 | 本次结果 |
| --- | --- | --- |
| 曲库、歌曲/歌单搜索、歌词、歌单详情、歌手歌曲 | Luna `client_token` | 已从零获取 token，并成功调用上述接口 |
| 扫码登录、个人资料、音乐漫游账号 | 个人 `access_code`、ticket、设备 EC 私钥、Guard 证书 | 已创建二维码；使用本机已授权账号成功验证个人资料签名请求；完整的新扫码确认/换票/刷新尚未由账号持有人完成实测 |
| TuneFree v1 播放解析 | TuneFree v1 Bearer access token | 已恢复请求合同和解码分支；未实测获授权的完整播放 |

安装包说明个人登录信息用于音乐漫游，播放仍沿用原解析与会员规则。**不能用个人 `access_code` 当作曲库 token，也不能用旧版 UMS/tf-pay token 当作 TuneFree v1 Bearer。**

本次后端实现：歌曲及歌单搜索、歌单详情/按 ID 导入、歌词（LRC + YRC）、歌手歌曲及基于歌曲关联信息的简要歌手资料、个人扫码绑定/状态/刷新/解绑、独立 v1 解析适配。未证实的榜单、分类歌单、推荐歌单、新歌、专辑详情、歌手专辑和专辑/歌手搜索，明确返回 `1012 / HTTP 501`。

播放适配只接受上游返回的普通 HTTP(S) 音频地址。返回非空 `encryption` 时明确拒绝，不把 CENC 文件冒充普通音频，也不把解密材料放入公开响应。新扫码授权完成和端到端完整音频播放是本次验证的缺口；搜索成功不代表这两项已验证。

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

早期尝试 `grant_type=client_credential` 获取 token 失败；将 `accessCode` 放入搜索的 `access-token` 也失败。这些失败不表示正确流程不可用，更不能据此宣称本机个人授权过期。

## 3. 上游曲库接口

### 3.1 通用参数

固定 User-Agent：`LunaOpenSDK/1.0.10`。平台请求 URL 是：

```text
https://open.douyin.com/api/luna/v1/platform{path}
```

下列通用字段在 URL query 中，`deviceId` 为当前客户端独立生成的标识，个人资料请求使用该次用户绑定的设备标识：

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

JSON body 均加 `"common_params": {}`。成功外壳为 `{ "err_no": 0, "err_msg": "", "data": {...} }`；非零业务码不能当作空结果缓存。

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

`search_type=track` 或 `playlist`，结果在 `data.result_groups[]`；对应 group.id 为 `tracks` 或 `playlists`。组内包含 `data[].entity.track/playlist`、`has_more:boolean`、`next_cursor:string`。`ab_param` 必须是字符串，`last_viewed_media` 是对象。

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

### 3.4 字段映射

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

本站此分支：

- 使用独立 `QISHUI_PARSE_ACCESS_TOKEN`；不读取本机 TuneFree 登录凭据，不复用旧 tf-pay。
- 继续要求本站有效会员；`prefetch=true` 保留现有不记历史行为。
- 进入现有8秒总预算、每源4个并发、有限执行器及同请求合并；qs 只走自身主解析，不发起跨源替换。
- 普通音频成功时保持 `source/id` 和 `actualSource/actualId` 为原曲，`lyricMatch=exact`。
- 明确期限的普通音频沿用现有缓存：最多1200秒并减30秒，期限未知/临近则不缓存。
- 有加密描述时返回1012，缺少 v1 token 时返回1003。上述拒绝行为已有测试，完整播放尚未实测。

## 6. 本站接口文档

以下接口均要求网站 Bearer 登录；用户归属只取服务端认证属性，不接受 body/query 的 userId。

| Method | Path | 请求/响应 |
| --- | --- | --- |
| POST | `/api/v1/music/qishui/auth/qr` | 无 body；创建当前用户 QR 会话 |
| GET | `/api/v1/music/qishui/auth/qr/{sessionId}` | 轮询并在确认后完成绑定 |
| GET | `/api/v1/music/qishui/account` | 当前用户的绑定状态及脱敏资料 |
| POST | `/api/v1/music/qishui/account/refresh` | 刷新当前用户授权 |
| DELETE | `/api/v1/music/qishui/account` | 删除本地绑定及待确认会话；不代表上游撤销授权 |

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

音乐接口继续使用现有响应 DTO：

| 请求 | qishui 支持情况 |
| --- | --- |
| `GET /api/v1/music/search?source=qs&keyword=...&type=song` | 支持；type=playlist 也支持；pageSize最大30 |
| `GET /api/v1/music/lyric?source=qs&id=...` | 支持；provider=qishui，official=true，KRC归一化为YRC |
| `GET /api/v1/music/playlist/detail?source=qs&id=<复合ID>` | 支持；pageSize最大100 |
| 现有用户歌单按 source + sourceId 导入 | 支持 qs 复合ID；未增加分享短链解析 |
| `GET /api/v1/music/artist/works?source=qs&id=...&type=song` | 支持；total未知时null |
| `GET /api/v1/music/artist?source=qs&ids=...` | 尽力提供歌曲关联中的简要资料；无歌曲时可能缺项 |
| `GET /api/v1/music/play?source=qs&id=...&quality=320k` | 需单独 v1 token；加密结果未支持 |
| 其余目录能力 | 明确1012/501，不伪装为空列表 |

授权错误沿用统一异常外壳：401未登录、404会话不存在/不属于本人/过期、409绑定已变更或未绑定、429创建过快；1003配置不完整、1004上游请求或格式失败、1009超时、1013忙。错误消息不附上游原文、签名URL、响应头或凭据。

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

密钥固定保存于部署环境，所有副本一致；丢失/更换后旧密文不能解密，需要重新授权。参数为空不影响旧音乐源启动，只有相关功能调用会报配置错误。目录查询不依赖个人凭据或 OAuth 配置。

个人凭据 AES-256-GCM 加密，随机12字节IV，128位标签，AAD绑定网站 userId及随机bindingId，密文格式 `v1.base64(iv).base64(ciphertext+tag)`。每次绑定/刷新轮换bindingId；刷新采用数据库条件更新，旧刷新不能覆盖新绑定或复活已删除记录。重新绑定也检查开始登录时的绑定代次。

二维码会话及操作锁在内存中。多实例部署必须把同一用户的授权创建、轮询、刷新、解绑固定到同一实例；数据库CAS只保护写回，不能代替跨节点授权会话取消。若需要无粘性的水平扩容，应先引入共享会话和分布式互斥。

曲库缓存只含使用 client_token 得到的公开目录结果；个人资料不进入全站 MusicCache。未来漫游/个人媒体功能必须用 `websiteUserId + bindingId` 隔离缓存。本次未增加个人漫游接口，也没有将本机已有授权导入网站数据库。

## 8. 验证与限制

2026-11-09 最终执行 `mvn -q clean verify`：71 个测试套件、443 项测试，失败/错误/跳过均为0。

自动化覆盖：凭据加密/篡改/换用户/换绑定代次、刷新CAS、首次绑定竞态、扫码归属/过期/限频/重复确认/失败重放、Guard签名与公钥恢复、HTTP请求方法与字段、刷新时保留原设备密钥及缺失openId的回退、19位ID、歌词时间换算和数值溢出、歌单参数校验、上游响应结构校验、重复分页游标、取消预算、加密媒体拒绝、源别名、旧解析器隔离。

新实现的 Java 客户端另做了真实网络检查：歌曲搜索返回3条、取得歌词、歌单详情返回3条；已有个人授权的 Guard 签名请求返回个人资料；新二维码返回PNG，首次轮询为 `new`。Java 生成的 CSR 已通过 `openssl req -verify -noout` 验签。这些检查调用生产客户端类，不依赖合成的成功响应。

真实网络验证与自动化测试应分开理解：QR创建、目录/歌词/歌单/歌手请求和已授权个人资料成功；新的扫码确认换票、真实刷新及带会员权限的完整播放仍未端到端测试。不会通过编造上游成功样本来代替这些验证。

用户最初给出的 mhub 项目（`https://github.com/jizizr/mhub`，参考提交 `8cda3d5dcd940677b86e060832baca36b2e25c8f` 的 `src/music/soda.rs`）另有匿名 `api.qishui.com/luna/search/track` 和 `beta-luna.douyin.com/luna/h5/seo_track` 路线。本次交叉探测成功，但所测 H5 播放器是试听；后端主适配没有偷偷切换到这条路线，没有搬入其下载/解密实现。
