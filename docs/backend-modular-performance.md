# 模块化改造的性能证据

2026-11-09。重构基线：444 项测试全部通过，随后加入边界及并发回归；最终 `clean verify` 共 455 项通过，零失败、零跳过。所有本地并发测试使用固定输入和模拟上游，不消耗真实音乐或内容服务额度。

| 场景 | 改造前 | 改造后 | 验证 |
| --- | --- | --- | --- |
| 12 个相同 QQ 歌单请求同时缓存未命中 | 12 次上游调用；已用失败测试复现 | 1 次上游调用，12 个调用均返回结果 | MusicCatalogConcurrencyTests |
| 12 个公开导航请求同时缓存未命中 | 原逻辑每请求 2 次批量 SQL，即 24 次 | 1 次分类 + 1 次链接查询，共 2 次 | NavigationConcurrencyTests |
| 导航缓存返回对象被修改 | 后续调用收到被改写的字段；失败测试已复现 | 入缓存、出缓存均复制 DTO 与嵌套链接 | NavigationCacheServiceTests |
| 写事务提交前失效缓存 | 并发读可能把旧数据库值重新放回缓存 | 提交后失效；事务内读不读写共享缓存；回滚保留已提交缓存 | NavigationCacheServiceTests、NavigationConcurrencyTests |
| 汽水旧请求延迟返回令牌错误 | 新刷新生成可能再次失效；测试发生 3 次令牌请求 | 不可变令牌/过期时间原子发布，只失效请求使用的那一代；2 次令牌请求 | QishuiTokenConcurrencyTests |

歌单场景减少 91.7% 重复上游调用；导航同一场景减少 91.7% SQL 次数。这是固定并发冷缓存条件下的请求合并收益，不是整体响应时间或吞吐提升比例。

## 保持的运行行为

- 音乐目录的原缓存键、30 分钟/10 分钟 TTL、分页归一化、来源映射均保留。榜单、榜单详情、歌单列表、推荐歌单、歌单详情、专辑详情及歌手作品使用同键合并。
- 不同键仍可并行；异常不缓存，失败后的调用可以恢复；错误实例/错误码保持；TTL 边界有测试。
- 搜索/播放既有请求预算、执行器上限、取消、熔断、质量回退和歌词策略不变。
- 导航仍采用两次批量查询，原先并非 N+1；只合并重复的并发加载。Caffeine 按键原子加载配合提交后失效，旧加载不能在失效完成后重新写入旧值。
- 默认管理员缓存仍为 1 项/60 秒，导航各视图为 200 项/5 分钟。事务读取绕过共享缓存，防止发布未提交数据或旧快照。
- 汽水曲库 client_token、每用户扫码 ticket/Guard、播放解析 Bearer 是不同凭据。令牌改造不改变个人授权流程。

## 全域审计结论

- 内容列表已有摘要投影；导航已有批量查询，未额外添加重复优化。
- 播放/搜索、影视和部分图像流程已有请求合并或有界执行器，继续使用。
- 生产 Tomcat、Hikari、JVM 内存已按小型 ECS 有界配置，未盲目增加线程、连接池或堆内存。
- 内容长事务与 AI/图像协议属于业务行为敏感区域，本次保持原事务和协议，完成职责拆分与回归保护。
- 本次不新增表、不修改字段、索引或持久化语义，部署无需额外 SQL 迁移。

## 复现

```sh
mvn -q -Dtest=MusicCatalogConcurrencyTests,MusicCatalogCacheTests,NavigationCacheServiceTests,NavigationConcurrencyTests,QishuiTokenConcurrencyTests test
mvn -q -Dtest=ModuleBoundaryTests,ArchitectureContractTests test
mvn -q clean verify
python3 tools/generate-api-index.py
```

`GET /api/admin/performance` 仍只向管理员提供有界请求/上游/缓存统计。对生产延迟应使用同样流量窗口及反向代理时序；不使用微小样本宣称 P95 改善。部署后的真实健康、搜索、扫码隔离和导航验证记录在发布文档中。
