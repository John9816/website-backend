# ChKSz 网易云七档音质解析设计

## 目标

DuckMusic 获取网易云歌曲播放地址时，优先调用 ChKSz `163_music`，完整支持其七档音质；ChKSz 不可用时继续使用现有 TuneFree 和跨平台回退链。QQ、酷我维持现有四档音质和解析行为。

七档网易云音质为：

| 请求值 | 显示名称 | TuneFree 兼容档位 |
| --- | --- | --- |
| `standard` | 标准 | `128k` |
| `exhigh` | 极高 | `320k` |
| `lossless` | 无损 | `flac` |
| `hires` | Hi-Res | `flac24bit` |
| `jyeffect` | 环绕声 | `flac24bit` |
| `sky` | 杜比全景声 | `flac24bit` |
| `jymaster` | 超清母带 | `flac24bit` |

## 非目标

- 不使用 ChKSz 替换网易云搜索、歌词、歌单、榜单或详情接口。
- 不接入 ChKSz 的 QQ、酷狗或其他音乐接口。
- 不代理音频字节，不下载或持久化第三方音频文件。
- 不保证每首网易云歌曲都具备七种实际资源；最终音质以解析响应为准。
- 不在客户端、代码仓库、日志、错误响应或监控标签中保存 ChKSz API Key。

## 已选方案

采用“平台专属请求音质 + 现有四档兼容层”。网易云请求支持七个 ChKSz 值，并继续接受旧客户端的四个历史值；QQ、酷我只接受历史四档。相比把七档压缩为四档，该方案能准确表达环绕声、杜比全景声和超清母带；相比让所有平台共用七档，该方案不会向 QQ、酷我暴露不存在的能力。

网易云历史值按以下规则归一化后请求 ChKSz：

| 旧请求值 | ChKSz 请求值 |
| --- | --- |
| `128k` | `standard` |
| `320k` | `exhigh` |
| `flac` | `lossless` |
| `flac24bit` | `hires` |

未传 `quality` 时，网易云默认使用 `lossless`，保持当前默认 `flac` 的语义；QQ、酷我仍默认 `flac`。`requestedQuality` 回显调用方传入并完成大小写规范化后的值，避免旧客户端因响应值变化失效。`actualQuality` 使用真正提供播放地址的上游音质值，允许返回七档值或 TuneFree 的四档值。

## 后端结构

### 音质模型

保留 `MusicQuality` 作为 TuneFree、QQ、酷我的四档模型，新增网易云专属的 `NeteasePlaybackQuality`。新增一个源感知的请求解析对象，保存：

- 调用方请求值和默认值
- 网易云 ChKSz 请求值
- TuneFree 兼容档位
- 用于缓存的规范值

`MusicService.play` 先解析 `source`，再按来源校验音质。网易云接受七档值及四个旧别名；其他来源收到七档专属值时返回 `INVALID_QUALITY`，错误消息列出该来源允许的值。

### ChKSz 客户端

新增独立的 `ChkszNeteaseMusicClient`，只负责：

```http
GET https://api.chksz.com/api/163_music
    ?id={网易云歌曲ID}
    &level={七档音质之一}
    &type=json
    &apikey={服务端Key}
```

客户端复用 `CLIENT_MUSIC_THIRD_PARTY`，单次调用受现有 5 秒总超时和 8 秒播放请求预算约束。成功条件为 HTTP 成功、业务 `code=200`、`data.url` 是合法的 HTTP(S) URL。响应映射到 `PlayInfo`：

- `id`、`name`、`artist`、`album`、`picUrl` 映射基础信息
- `url` 映射 `playUrl`
- `level` 映射 `actualQuality`；缺失时使用本次 ChKSz 请求档位
- `size` 映射 `fileSize`
- `source` 和 `actualSource` 均为 `netease`

客户端不记录完整请求 URL、API Key、完整响应或播放 URL。允许记录不含凭据的 HTTP 状态、业务状态、歌曲 ID、音质和回退原因。

### 配置

新增 `app.music.chksz` 配置，至少包含 `enabled`、`base-url` 和 `api-key`。生产环境使用：

```yaml
app:
  music:
    chksz:
      enabled: ${CHKSZ_ENABLED:true}
      base-url: ${CHKSZ_BASE_URL:https://api.chksz.com}
      api-key: ${CHKSZ_API_KEY:}
```

`deploy/production/backend.env.example` 只增加空占位符，不写真实 Key。Key 为空或功能被关闭时直接跳过 ChKSz，不产生外部请求。

## 播放数据流

网易云播放顺序固定为：

1. 使用规范化后的七档值调用 ChKSz 一次。
2. ChKSz 成功时校验播放 URL，写入现有 20 分钟播放缓存并返回。
3. ChKSz 失败或无可用 URL 时，将请求映射到四档兼容值，进入现有 TuneFree 质量降级链。
4. TuneFree 失败后，继续现有跨平台搜索和播放回退。

QQ 仍执行 `TuneFree -> QQ 文本接口 -> 跨平台回退`，酷我仍执行 `TuneFree -> 跨平台回退`。现有 `music.play.resolverOrder` 的管理配置继续控制旧解析阶段；ChKSz 只作为网易云 `primary` 阶段内部的首选解析器，不改变其他来源的配置语义。

ChKSz 不在一次播放请求中逐档重试。这样既遵守每分钟和每日额度，也为 TuneFree、跨平台回退保留 8 秒预算。单曲目标音质不存在时，自动降级由 ChKSz 返回的实际 `level` 或现有 TuneFree 四档降级链完成。

缓存键继续包含来源、歌曲 ID 和音质。网易云使用七档规范值作为缓存音质，因此同一歌曲的 `jymaster`、`sky`、`jyeffect` 等结果互不覆盖；旧四档别名与对应七档值共用缓存条目。响应的 `requestedQuality` 在返回前按当前请求重新填充，防止共享缓存改变旧客户端看到的值。

## 故障、限流与配额

ChKSz 的业务失败不阻断现有播放回退：

- `400`：视为本次参数或资源失败，记录 30 秒负缓存并回退，不重复相同请求。
- `401`、`403`：视为配置或授权故障，打开 10 分钟全局冷却并回退，日志不得包含 Key。
- `402`：视为额度耗尽，冷却到下一个中国标准时间（UTC+8）零点并回退，避免同日持续请求。
- `429`：读取 `Retry-After` 建立全局冷却；缺失或非法时使用 60 秒，合法值最长接受 24 小时。本次请求立即回退，不等待、不自动重试。
- `503`、网络异常、超时或非法 JSON：计入第三方熔断器并立即回退。
- HTTP 成功但 `code` 非 200、`data` 为空或没有合法 URL：记录 30 秒负缓存后回退。

负缓存以 `歌曲 ID + 七档音质` 为键，避免短时间重复探测同一失败资源；基础设施错误使用独立 `ThirdPartyCircuitBreaker`，沿用现有失败阈值和恢复窗口。全局冷却只影响 ChKSz，不影响 TuneFree 或跨平台回退。配额响应头只用于脱敏运维指标，不返回客户端，也不记录请求 URL。若后续回退成功，用户获得正常播放响应；只有所有解析器均失败时才沿用现有音乐业务错误。

## Flutter 交互与兼容

DuckMusic 音质选择器按当前音乐来源提供选项：

- 网易云：标准、极高、无损、Hi-Res、环绕声、杜比全景声、超清母带。
- QQ、酷我：保持 `128k`、`320k`、`flac`、`flac24bit` 对应的现有四档文案。

切换音乐来源时，如果当前音质不被新来源支持，则按兼容表选择最接近的四档值；切回网易云时恢复用户最近一次选择的网易云档位。偏好按来源保存，避免选择 `jymaster` 后切到 QQ 仍发送无效值。

旧版本客户端无需更新即可继续发送四档值。新版本客户端发送七档值，但不得在 UI 中把“接口支持”描述为“当前歌曲一定可用”；播放结果页或调试信息以 `actualQuality` 为准。

## 测试与验收

后端单元测试覆盖：

- 网易云七档值和四个旧别名的解析、默认值、缓存规范值及 TuneFree 映射。
- QQ、酷我拒绝七档专属值，原四档解析不回归。
- ChKSz 七种 `level` 均生成正确请求，并正确映射成功响应字段。
- API Key 缺失或关闭时零 ChKSz 请求。
- `400/401/402/403/429/503`、超时、无效 JSON、业务失败和缺失 URL 均只调用一次并进入 TuneFree。
- `Retry-After`、负缓存和熔断期间不重复请求 ChKSz。
- ChKSz 成功时不调用 TuneFree；ChKSz 失败时保留现有四档降级、跨平台回退和 8 秒预算。
- 七档缓存隔离、旧别名缓存复用、20 分钟 TTL，以及缓存命中时 `requestedQuality` 正确回显。
- 日志和异常文本不包含测试 API Key。

Flutter 测试覆盖：

- 网易云显示七档，QQ、酷我显示四档。
- 来源切换时使用兼容值，并按来源恢复最近选择。
- 新旧音质值序列化和 `/play` 请求参数正确。
- `requestedQuality` 与 `actualQuality` 不同时，播放不失败且界面不误报目标音质已获得。

验收时使用测试 Key 和固定歌曲 ID，各验证一次 `standard` 与 `jymaster` 请求，确认 ChKSz 优先命中；随后关闭 ChKSz 或使用模拟失败响应，确认 TuneFree 与跨平台回退仍可播放。自动化测试使用本地模拟服务，不消耗真实额度。

## 发布顺序

1. 先部署后端代码，但保持 `CHKSZ_ENABLED=false`，验证旧解析链和健康检查。
2. 在 ECS 私有环境文件中设置 `CHKSZ_API_KEY`，确保文件权限和日志脱敏。
3. 开启 ChKSz，用少量网易云歌曲验证七档请求、实际音质、缓存和回退。
4. 发布支持来源专属音质选择的 DuckMusic 客户端，由真机完成交互和播放验证。
5. 观察授权、配额、限流、熔断和回退指标；异常时只需关闭 `CHKSZ_ENABLED`，无需回滚客户端。
