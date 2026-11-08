# 后端接口契约索引

由 `tools/generate-api-index.py` 从重构前的契约基线生成。`ArchitectureContractTests` 每次构建会从实际 Spring 路由、Jackson 模型和 JPA 实体重新采集并比较，重构不得通过重写基线绕过差异。

共 189 条路由映射（包含同一 handler 的路径别名）、140 个 JSON DTO/枚举、34 个数据库实体。路径、参数注解、返回类型、JSON 属性、实体注解均纳入比较；Java 包名前缀不纳入对外契约。

请求与响应示例见 [前端接口文档](frontend-api.md)，汽水的上游协议、独立扫码及后端适配见 [黑源接口文档](tunefree-next-qishui-api.md)。

| 方法与路径映射 | 返回类型 |
| --- | --- |
| `{ [/api/public/kb/** &#124;&#124; /api/share/doc/** &#124;&#124; /api/admin/kb/** &#124;&#124; /api/user/kb/** &#124;&#124; /api/v1/kb/** &#124;&#124; /api/kb/**]}` | `org.springframework.http.ResponseEntity<ApiResponse<Void>>` |
| `{DELETE [/api/admin/categories/{id} &#124;&#124; /api/user/categories/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/admin/configs/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/admin/content/articles/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/admin/image/history/{id} &#124;&#124; /api/user/image/history/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/admin/links/{id} &#124;&#124; /api/user/links/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/admin/redeem-codes/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/admin/users/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/content/automation/{taskId}]}` | `org.springframework.http.ResponseEntity<Void>` |
| `{DELETE [/api/content/templates/{templateId}]}` | `org.springframework.http.ResponseEntity<Void>` |
| `{DELETE [/api/user/ai/conversations/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/user/music/favorites]}` | `ApiResponse<Void>` |
| `{DELETE [/api/user/music/history/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/user/music/playlist-favorites]}` | `ApiResponse<Void>` |
| `{DELETE [/api/user/music/playlists/{id}/items/{itemId}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/user/music/playlists/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/user/music/shares]}` | `ApiResponse<Void>` |
| `{DELETE [/api/v1/books/user-sources/{id}]}` | `ApiResponse<Void>` |
| `{DELETE [/api/v1/music/qishui/account]}` | `org.springframework.http.ResponseEntity<ApiResponse<Void>>` |
| `{GET [/api/admin/categories &#124;&#124; /api/user/categories]}` | `ApiResponse<List<CategoryView>>` |
| `{GET [/api/admin/categories/{id} &#124;&#124; /api/user/categories/{id}]}` | `ApiResponse<CategoryView>` |
| `{GET [/api/admin/configs/{id}]}` | `ApiResponse<SysConfigView>` |
| `{GET [/api/admin/configs]}` | `ApiResponse<List<SysConfigView>>` |
| `{GET [/api/admin/content/articles/{id}]}` | `ApiResponse<ContentArticleView>` |
| `{GET [/api/admin/content/articles]}` | `ApiResponse<PageView<ContentArticleView>>` |
| `{GET [/api/admin/content/automation/run/{jobId}]}` | `ApiResponse<ContentAgentRunJobView>` |
| `{GET [/api/admin/content/automation]}` | `ApiResponse<ContentAutomationView>` |
| `{GET [/api/admin/content/autopilot]}` | `ApiResponse<Map<String, Object>>` |
| `{GET [/api/admin/content/hot]}` | `ApiResponse<ContentHotTopicsView>` |
| `{GET [/api/admin/content/status]}` | `ApiResponse<ContentFactoryStatusView>` |
| `{GET [/api/admin/image/generate/{taskId} &#124;&#124; /api/user/image/generate/{taskId}]}` | `ApiResponse<ImageTaskView>` |
| `{GET [/api/admin/image/history &#124;&#124; /api/user/image/history]}` | `ApiResponse<PageView<GeneratedImageView>>` |
| `{GET [/api/admin/image/models &#124;&#124; /api/user/image/models]}` | `ApiResponse<List<ImageModelView>>` |
| `{GET [/api/admin/image/tasks &#124;&#124; /api/user/image/tasks]}` | `ApiResponse<PageView<ImageTaskView>>` |
| `{GET [/api/admin/links &#124;&#124; /api/user/links]}` | `ApiResponse<List<LinkView>>` |
| `{GET [/api/admin/links/{id} &#124;&#124; /api/user/links/{id}]}` | `ApiResponse<LinkView>` |
| `{GET [/api/admin/performance]}` | `ApiResponse<PerformanceMetrics$Snapshot>` |
| `{GET [/api/admin/redeem-codes]}` | `ApiResponse<PageView<RedeemCodeAdminView>>` |
| `{GET [/api/admin/users/{id}]}` | `ApiResponse<AdminUserDetailView>` |
| `{GET [/api/admin/users]}` | `ApiResponse<PageView<AdminUserView>>` |
| `{GET [/api/content/automation/search]}` | `org.springframework.http.ResponseEntity<org.springframework.data.domain.Page<Map<String, Object>>>` |
| `{GET [/api/content/automation/statistics]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{GET [/api/content/automation/{taskId}]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{GET [/api/content/automation]}` | `org.springframework.http.ResponseEntity<org.springframework.data.domain.Page<Map<String, Object>>>` |
| `{GET [/api/content/templates/by-category]}` | `org.springframework.http.ResponseEntity<List<Map<String, Object>>>` |
| `{GET [/api/content/templates/categories]}` | `org.springframework.http.ResponseEntity<List<String>>` |
| `{GET [/api/content/templates/search]}` | `org.springframework.http.ResponseEntity<org.springframework.data.domain.Page<Map<String, Object>>>` |
| `{GET [/api/content/templates/statistics]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{GET [/api/content/templates/{templateId}]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{GET [/api/content/templates]}` | `org.springframework.http.ResponseEntity<org.springframework.data.domain.Page<Map<String, Object>>>` |
| `{GET [/api/public/app/update], produces [application/json]}` | `org.springframework.http.ResponseEntity<ApiResponse<AppUpdateView>>` |
| `{GET [/api/public/categories]}` | `ApiResponse<List<CategoryView>>` |
| `{GET [/api/public/image/shared]}` | `ApiResponse<PageView<GeneratedImageView>>` |
| `{GET [/api/public/links]}` | `ApiResponse<List<LinkView>>` |
| `{GET [/api/public/metrics]}` | `ApiResponse<PerformanceMetrics$Snapshot>` |
| `{GET [/api/public/music/installer/latest]}` | `ApiResponse<Map<String, Object>>` |
| `{GET [/api/public/music/share/{token}]}` | `ApiResponse<MusicPublicShareView>` |
| `{GET [/api/public/nav]}` | `ApiResponse<List<CategoryView>>` |
| `{GET [/api/user/ai/conversations/{id}/messages]}` | `ApiResponse<PageView<AiChatMessageView>>` |
| `{GET [/api/user/ai/conversations/{id}]}` | `ApiResponse<AiConversationView>` |
| `{GET [/api/user/ai/conversations]}` | `ApiResponse<PageView<AiConversationView>>` |
| `{GET [/api/user/ai/messages/{id}/audio]}` | `org.springframework.http.ResponseEntity<byte[]>` |
| `{GET [/api/user/ai/models]}` | `ApiResponse<List<AiModelView>>` |
| `{GET [/api/user/ai/voices]}` | `ApiResponse<List<AiVoiceView>>` |
| `{GET [/api/user/credits]}` | `ApiResponse<UserCreditView>` |
| `{GET [/api/user/me]}` | `ApiResponse<CurrentUserView>` |
| `{GET [/api/user/membership]}` | `ApiResponse<MembershipView>` |
| `{GET [/api/user/music/favorites/status]}` | `ApiResponse<MusicFavoriteStatusView>` |
| `{GET [/api/user/music/favorites]}` | `ApiResponse<PageView<MusicFavoriteView>>` |
| `{GET [/api/user/music/history]}` | `ApiResponse<PageView<MusicHistoryView>>` |
| `{GET [/api/user/music/playlist-favorites/status]}` | `ApiResponse<PlaylistFavoriteStatusView>` |
| `{GET [/api/user/music/playlists/{id}]}` | `ApiResponse<UserPlaylistDetailView>` |
| `{GET [/api/user/music/playlists]}` | `ApiResponse<PageView<UserPlaylistView>>` |
| `{GET [/api/user/music/shares/status]}` | `ApiResponse<MusicShareView>` |
| `{GET [/api/v1/books/chapters]}` | `ApiResponse<BookChapterListView>` |
| `{GET [/api/v1/books/content]}` | `ApiResponse<BookContentView>` |
| `{GET [/api/v1/books/detail]}` | `ApiResponse<BookView>` |
| `{GET [/api/v1/books/discover]}` | `ApiResponse<BookDiscoverView>` |
| `{GET [/api/v1/books/search]}` | `ApiResponse<BookSearchPageView>` |
| `{GET [/api/v1/books/sources]}` | `ApiResponse<List<BookSourceCatalogView>>` |
| `{GET [/api/v1/books/user-sources]}` | `ApiResponse<List<BookUserSourceView>>` |
| `{GET [/api/v1/content/assets/{filename}]}` | `org.springframework.http.ResponseEntity<byte[]>` |
| `{GET [/api/v1/image/file/{filename}]}` | `org.springframework.http.ResponseEntity<byte[]>` |
| `{GET [/api/v1/music/album/detail]}` | `ApiResponse<AlbumDetailView>` |
| `{GET [/api/v1/music/artist/works]}` | `ApiResponse<ArtistWorksView>` |
| `{GET [/api/v1/music/artist]}` | `ApiResponse<List<ArtistDetailView>>` |
| `{GET [/api/v1/music/lyric]}` | `ApiResponse<LyricView>` |
| `{GET [/api/v1/music/new]}` | `ApiResponse<ToplistDetailView>` |
| `{GET [/api/v1/music/play]}` | `ApiResponse<PlayInfo>` |
| `{GET [/api/v1/music/playlist/detail]}` | `ApiResponse<PlaylistDetailView>` |
| `{GET [/api/v1/music/playlist/recommend]}` | `ApiResponse<PlaylistListView>` |
| `{GET [/api/v1/music/playlist]}` | `ApiResponse<PlaylistListView>` |
| `{GET [/api/v1/music/qishui/account]}` | `org.springframework.http.ResponseEntity<ApiResponse<QishuiAccountView>>` |
| `{GET [/api/v1/music/qishui/auth/qr/{sessionId}]}` | `org.springframework.http.ResponseEntity<ApiResponse<QishuiAccountView>>` |
| `{GET [/api/v1/music/search]}` | `ApiResponse<SearchResultView>` |
| `{GET [/api/v1/music/toplist/detail]}` | `ApiResponse<ToplistDetailView>` |
| `{GET [/api/v1/music/toplist]}` | `ApiResponse<ToplistListView>` |
| `{GET [/api/v1/user/avatar/{filename}]}` | `org.springframework.http.ResponseEntity<?>` |
| `{GET [/api/v1/videos/categories]}` | `ApiResponse<List<VideoCategoryView>>` |
| `{GET [/api/v1/videos/detail]}` | `ApiResponse<VideoDetailView>` |
| `{GET [/api/v1/videos/discover]}` | `ApiResponse<VideoSearchPageView>` |
| `{GET [/api/v1/videos/filters]}` | `ApiResponse<VideoFilterView>` |
| `{GET [/api/v1/videos/play]}` | `ApiResponse<VideoPlayView>` |
| `{GET [/api/v1/videos/search]}` | `ApiResponse<VideoSearchPageView>` |
| `{GET [/api/v1/videos/sources]}` | `ApiResponse<List<VideoSourceView>>` |
| `{GET [/api/v1/videos/{id}/detail]}` | `ApiResponse<VideoDetailView>` |
| `{GET [/api/v1/videos/{id}/play]}` | `ApiResponse<VideoPlayView>` |
| `{GET [/health]}` | `ApiResponse<Map<String, String>>` |
| `{GET [/music/album/detail]}` | `ApiResponse<AlbumDetailView>` |
| `{GET [/music/artist/works]}` | `ApiResponse<ArtistWorksView>` |
| `{GET [/music/artist]}` | `ApiResponse<List<ArtistDetailView>>` |
| `{GET [/music/lyric]}` | `ApiResponse<LyricView>` |
| `{GET [/music/new]}` | `ApiResponse<ToplistDetailView>` |
| `{GET [/music/play]}` | `ApiResponse<PlayInfo>` |
| `{GET [/music/playlist/detail]}` | `ApiResponse<PlaylistDetailView>` |
| `{GET [/music/playlist/recommend]}` | `ApiResponse<PlaylistListView>` |
| `{GET [/music/playlist]}` | `ApiResponse<PlaylistListView>` |
| `{GET [/music/search]}` | `ApiResponse<SearchResultView>` |
| `{GET [/music/toplist/detail]}` | `ApiResponse<ToplistDetailView>` |
| `{GET [/music/toplist]}` | `ApiResponse<ToplistListView>` |
| `{GET [/nav/categories]}` | `ApiResponse<List<CategoryView>>` |
| `{GET [/nav]}` | `ApiResponse<List<CategoryView>>` |
| `{GET [/updates/latest.json], produces [application/json]}` | `org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>` |
| `{GET [/updates/{filename:.+}]}` | `org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>` |
| `{PATCH [/api/admin/image/history/{id}/share &#124;&#124; /api/user/image/history/{id}/share]}` | `ApiResponse<GeneratedImageView>` |
| `{PATCH [/api/user/music/playlists/{id}]}` | `ApiResponse<UserPlaylistView>` |
| `{PATCH [/api/v1/books/user-sources/{id}/enabled]}` | `ApiResponse<BookUserSourceView>` |
| `{POST [/api/admin/categories &#124;&#124; /api/user/categories]}` | `ApiResponse<CategoryView>` |
| `{POST [/api/admin/change-password &#124;&#124; /api/user/change-password]}` | `ApiResponse<Void>` |
| `{POST [/api/admin/configs]}` | `ApiResponse<SysConfigView>` |
| `{POST [/api/admin/content/articles/generate-async]}` | `ApiResponse<ContentAgentRunJobView>` |
| `{POST [/api/admin/content/articles/generate]}` | `ApiResponse<ContentArticleView>` |
| `{POST [/api/admin/content/articles/{id}/publish]}` | `ApiResponse<Map<String, Object>>` |
| `{POST [/api/admin/content/articles/{id}/revise]}` | `ApiResponse<ContentArticleView>` |
| `{POST [/api/admin/content/articles/{id}/visuals]}` | `ApiResponse<ContentArticleView>` |
| `{POST [/api/admin/content/articles/{id}/wechat-draft]}` | `ApiResponse<Map<String, Object>>` |
| `{POST [/api/admin/content/automation/jobs/retry]}` | `ApiResponse<ContentAutomationView>` |
| `{POST [/api/admin/content/automation/run]}` | `ApiResponse<ContentAgentRunJobView>` |
| `{POST [/api/admin/content/autopilot/run]}` | `ApiResponse<ContentAgentRunJobView>` |
| `{POST [/api/admin/image/edit &#124;&#124; /api/user/image/edit]}` | `ApiResponse<ImageTaskView>` |
| `{POST [/api/admin/image/generate &#124;&#124; /api/user/image/generate]}` | `ApiResponse<ImageTaskView>` |
| `{POST [/api/admin/image/history/{id}/retry &#124;&#124; /api/user/image/history/{id}/retry]}` | `ApiResponse<ImageTaskView>` |
| `{POST [/api/admin/links &#124;&#124; /api/user/links]}` | `ApiResponse<LinkView>` |
| `{POST [/api/admin/membership/batch-extend]}` | `ApiResponse<Void>` |
| `{POST [/api/admin/redeem-codes/{id}/revoke]}` | `ApiResponse<RedeemCodeAdminView>` |
| `{POST [/api/admin/redeem-codes]}` | `ApiResponse<RedeemCodeBatchView>` |
| `{POST [/api/admin/users/{id}/reset-password]}` | `ApiResponse<Void>` |
| `{POST [/api/admin/users]}` | `ApiResponse<AdminUserView>` |
| `{POST [/api/auth/email-code]}` | `ApiResponse<EmailVerificationCodeView>` |
| `{POST [/api/auth/login]}` | `ApiResponse<LoginResponse>` |
| `{POST [/api/auth/logout]}` | `ApiResponse<Void>` |
| `{POST [/api/auth/password-reset/confirm]}` | `ApiResponse<Void>` |
| `{POST [/api/auth/password-reset/email-code]}` | `ApiResponse<EmailVerificationCodeView>` |
| `{POST [/api/auth/refresh]}` | `ApiResponse<LoginResponse>` |
| `{POST [/api/auth/register]}` | `ApiResponse<LoginResponse>` |
| `{POST [/api/content/automation/{taskId}/disable]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{POST [/api/content/automation/{taskId}/enable]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{POST [/api/content/automation/{taskId}/execute]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{POST [/api/content/automation]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{POST [/api/content/templates/{templateId}/duplicate]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{POST [/api/content/templates]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{POST [/api/user/ai/conversations/{id}/messages], params [stream=true], produces [text/event-stream]}` | `org.springframework.web.servlet.mvc.method.annotation.SseEmitter` |
| `{POST [/api/user/ai/conversations/{id}/messages]}` | `ApiResponse<AiConversationReplyView>` |
| `{POST [/api/user/ai/conversations]}` | `ApiResponse<AiConversationView>` |
| `{POST [/api/user/ai/messages/{id}/audio]}` | `ApiResponse<AiChatMessageView>` |
| `{POST [/api/user/ai/tts]}` | `org.springframework.http.ResponseEntity<byte[]>` |
| `{POST [/api/user/avatar]}` | `ApiResponse<CurrentUserView>` |
| `{POST [/api/user/check-in]}` | `ApiResponse<UserCreditView>` |
| `{POST [/api/user/membership/redeem]}` | `ApiResponse<RedeemResultView>` |
| `{POST [/api/user/music/favorites/status]}` | `ApiResponse<List<MusicFavoriteStatusView>>` |
| `{POST [/api/user/music/favorites]}` | `ApiResponse<MusicFavoriteView>` |
| `{POST [/api/user/music/playlist-favorites]}` | `ApiResponse<UserPlaylistView>` |
| `{POST [/api/user/music/playlists/import]}` | `ApiResponse<UserPlaylistView>` |
| `{POST [/api/user/music/playlists/{id}/items]}` | `ApiResponse<UserPlaylistItemView>` |
| `{POST [/api/user/music/playlists/{id}/sync]}` | `ApiResponse<UserPlaylistView>` |
| `{POST [/api/user/music/playlists]}` | `ApiResponse<UserPlaylistView>` |
| `{POST [/api/user/music/shares]}` | `ApiResponse<MusicShareView>` |
| `{POST [/api/v1/books/user-sources/import]}` | `ApiResponse<BookUserSourceView>` |
| `{POST [/api/v1/music/qishui/account/refresh]}` | `org.springframework.http.ResponseEntity<ApiResponse<QishuiAccountView>>` |
| `{POST [/api/v1/music/qishui/auth/qr]}` | `org.springframework.http.ResponseEntity<ApiResponse<QishuiAccountView>>` |
| `{PUT [/api/admin/categories/{id} &#124;&#124; /api/user/categories/{id}]}` | `ApiResponse<CategoryView>` |
| `{PUT [/api/admin/configs/{id}]}` | `ApiResponse<SysConfigView>` |
| `{PUT [/api/admin/content/articles/{id}]}` | `ApiResponse<ContentArticleView>` |
| `{PUT [/api/admin/content/autopilot]}` | `ApiResponse<Map<String, Object>>` |
| `{PUT [/api/admin/links/{id} &#124;&#124; /api/user/links/{id}]}` | `ApiResponse<LinkView>` |
| `{PUT [/api/admin/users/{id}]}` | `ApiResponse<AdminUserView>` |
| `{PUT [/api/content/automation/{taskId}]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{PUT [/api/content/templates/{templateId}]}` | `org.springframework.http.ResponseEntity<Map<String, Object>>` |
| `{PUT [/api/user/profile]}` | `ApiResponse<CurrentUserView>` |
