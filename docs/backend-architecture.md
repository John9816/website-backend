# 后端模块与兼容边界

2026-11-09。本次保持 Spring Boot 单体部署和现有数据库，按业务域组织全部业务代码。适配当前 2 核、约 1.6 GiB ECS，不增加网络服务或中间件。

## 模块布局

业务根目录：`src/main/java/com/example/website/module/`。模块内部按 `api/controller`、`api/dto`、`application`、`domain`、`infrastructure/persistence` 分层。`application` 包含用例服务以及现有上游协议适配器；协议客户端仍按供应商独立维护。

| 模块 | 负责内容 |
| --- | --- |
| identity | 登录、用户、认证缓存、刷新令牌、默认管理员身份、额度 |
| administration | 跨业务的管理员账号管理、统计和已有删除清理流程 |
| membership | 会员、兑换码、权限和额度规则 |
| navigation | 分类、链接、公开导航及导航缓存 |
| music | 四来源音乐、搜索/目录/播放/歌词、扫码授权、收藏/历史/歌单/分享 |
| content | 文章管理、写稿、审稿、图片编排、公众号投递、自动任务 |
| ai | 对话、流式调用、语音与会话历史 |
| images | 图像任务、生成/编辑、历史和分享 |
| assets | 文件与图片存储 |
| knowledge | 知识库文档、空间、标签、版本和分享 |
| reading | 阅读记录与收藏 |
| video | 影视源、目录、播放和兼容解析 |
| settings | 系统配置 |
| distribution | 应用更新与发行信息 |
| operations | 运维与性能查询 |
| compatibility | 旧 API 兼容入口 |

公共配置仍在 `config/`，响应/错误处理在 `common/`，无业务归属的分页模型、并发合并、提交后回调在 `shared/`。JPA 扫描入口改为模块根，实体及表映射不变。

## 依赖约束

`ModuleBoundaryTests` 随 Maven 测试执行，检查源码引用及包归属：

- 业务类不能重新进入根目录的 controller/service/dto/entity/repository。
- domain 不反向依赖 application、API 或持久化；持久化不依赖用例或控制器。
- 控制器不能绕过用例服务直接访问仓储，shared 不能依赖业务模块。
- 跨模块依赖必须在 `src/test/resources/contracts/module-dependencies.json` 中显式声明，并且不能产生循环。
- 直接跨模块仓储访问只保留三个明确位置：`AdminUserService` 的账号管理、`AdminUserDataService` 的管理员统计/清理、`MembershipService` 的既有用户锁定。新增位置需要具体设计与测试，不默认放开。

默认管理员缓存归还 identity，跨领域管理操作上移 administration，消除了原先 identity 与 navigation/music/content 等模块的循环。管理员删除仍在原事务内按原顺序执行相同 SQL，锁定、自删保护、最后一个管理员保护、令牌撤销不变。

## 服务职责拆分

`MusicService` 从 1242 行降到 74 行兼容入口，委托给 `MusicSearchService`（搜索预算与缓存）、`MusicCatalogService`（目录分页/缓存/来源路由）、`MusicPlaybackService`（解析竞速、校验和回退）及已有 `MusicLyricService`。播放请求 8 秒预算、4 个并发解析名额、质量/来源回退、缓存有效期、错误码和历史记录行为保持原样。

`ContentArticleService` 从 2476 行拆成文章/投递流程编排（1464 行）、`ArticleDraftWriter`（565 行，模型选择、生成/解析和失败回退）、`ArticleDraftText`（501 行，无 I/O 的文本规则）及内部生成结果模型。原提示词、阈值、标题规则、发布门禁、幂等锁和事务注解保留。

业务量较大的单来源协议客户端、AI 流式协议、图像格式适配仍保留独立实现；这次不重写上游协议或更换运行框架。现有内容生成/投递事务涉及外部副作用，审计后保留事务语义；将其改成状态机/任务事务需要单独设计补偿和幂等，不把单纯缩短事务当作无行为差异优化。

## HTTP 与数据契约

见 [接口索引](api-contract-index.md) 和 [前端接口说明](frontend-api.md)。重构前采集的 189 条路由映射、140 个 JSON 模型、34 个 JPA 实体保存在 `public-api-and-entities.json`，构建时与真实 Spring/Jackson/JPA 模型比较。不得使用 `-DwriteApiContract=true` 更新基线来掩盖重构差异。

结构契约不能代替功能测试：鉴权、所有权、会员规则、分页、别名、缓存、取消、失败恢复、发布幂等仍由对应服务与集成测试覆盖。包名改变不要求前端或数据库迁移。

## 性能与验证

见 [性能证据](backend-modular-performance.md)。不以移动目录或减少代码行数作为性能收益。本次只报告可复现的调用数量、缓存隔离和并发行为；线上上游延迟波动不能用于推断整体吞吐或 P95 提升。
