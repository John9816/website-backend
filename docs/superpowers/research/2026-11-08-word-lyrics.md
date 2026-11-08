# QQ / 酷我逐字歌词后端接入

日期：2026-11-08。以下记录实现阶段的本地验证。用户后续授权发布后，已提交、推送并部署到 ECS；上线详情见 [发布记录](2026-11-08-word-lyrics-release.md)。

## 原因及实现

旧实现 `QqMusicClient.fetchLyric`、`KuwoMusicClient.fetchLyric` 只读取普通歌词，`MusicService` 固定把 `karaokeLyrics` 设为 null。这是本项目的接口接入缺失，不能据此判断平台没有逐字歌词。

新增 `fetchLyrics` 优先读取同平台、同歌曲 ID 的逐字接口，成功后同时返回普通歌词和逐字歌词；普通接口 `fetchLyric` 保持可用，增强接口失败时回退。原有第三方兜底仍由服务层处理。

- QQ：`music.musichallSong.PlayLyricInfo.GetPlayLyricInfo`，直接以 songMid 请求。QRC 为十六进制、QQ DES 变体、zlib、UTF-8；标准 JCE DESede 与其不兼容。
- 酷我：`https://newlyric.kuwo.cn/newlyric.lrc`，请求携带原歌曲 rid；响应为带头部的 zlib/base64/XOR/GB18030 数据。逐字标签的两个数值需结合 `[kuwo:八进制值]` 换算起始偏移与持续时间。
- `WordLyricCodec` 将真实字词时间转换为绝对毫秒 YRC。保留空格、行偏移和原文字词切分；有坏时间行时保留完整普通歌词，不伪造逐字时间。解码/解压输出限制 2 MiB，网络调用使用现有 5 秒预算。
- 歌词缓存键升级 v3；有逐字数据保留 6 小时，普通歌词仅保留 10 分钟，避免一次增强请求失败持续遮蔽逐字数据。
- 现有 `LyricView` / `LyricInfo` JSON 合约不变，不需要数据库迁移；旧客户端继续读取 `lineLyrics`。

协议核对时阅读了 `lyswhut/lx-music-desktop` 的 `kw/util.js`、`tx/lyric.js`、`tx/qrcDecode.js`，`CN-Grace/musedl` 的 `platforms/kuwo/lyrics.go`，以及 `EllanStudio/qqmusic-api` 的客户端实现；本项目没有增加这些仓库为运行依赖，也未复制其整体实现。`QrcCipher` 使用自行组织的通用置换/轮函数实现协议，算法常量与协议已通过独立加密的合成文本及线上响应验证。测试夹具为合成文字，不包含整首歌曲歌词。

## 实测

使用本地修改后的 Java 客户端直接读取平台接口，不经过旧的生产后端缓存：

| 来源 | ID | 歌曲 | 歌词行 | 字词时间片 |
| --- | --- | --- | ---: | ---: |
| QQ | 003UkWuI0E8U0l | 孤勇者 | 69 | 589 |
| QQ | 0039MnYb0qxYhV | 晴天 | 63 | 602 |
| 酷我 | 198554068 | 孤勇者 | 69 | 589 |
| 酷我 | 228908 | 晴天 | 63 | 602 |

以上为该次采样，包含标题/署名行，不代表所有歌曲均有逐字数据。单次读取耗时 62–384 ms，不是负载或延迟基准。

四份结果进一步交给 DuckMusic 的生产 `parseKaraokeLrc`：行数、文字、行时间全部保持，逐字片段在开始/结束时分别为 0/1 进度。未声称已在模拟器连接本地后端播放这四首歌曲，线上也尚未切换。

## 自动验证

- 解码器、客户端请求/失败回退、服务返回/缓存分别先写失败测试，再实现。
- 覆盖真实 QRC 变体、LRCX 八进制系数、偏移、空格、普通歌词、倒序时间、截断/损坏/超限负载、网络失败和旧缓存隔离。
- Maven 全量测试：340 项通过，0 失败、0 错误、0 跳过。
- `git diff --check` 通过。
- 本地证据：`target/qa/word-lyrics/`，含红绿测试日志、`full-tests.log`、`live-summary.json`、实测 JSON 和临时验证程序。

部署后还需要从 ECS 的网络环境抽查同样的四个 ID，确认上游可达及 `karaokeLyrics` 非空；本次没有执行部署。
