# ncat21 DuckMusic Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a bounded ncat21 video catalog to DuckMusic with categories, search, filters, detail/episode selection, playback handoff, and a platform-safe casting entry point backed by ECS.

**Architecture:** Spring Boot owns all ncat21 HTML retrieval, fixed Jsoup parsing, URL validation, response normalization, caching and upstream failure reporting. Flutter consumes a versioned `/api/v1/videos/**` contract, renders a dedicated video section, plays only validated direct media URLs with `video_player` when available, and falls back to the upstream playback page when the source does not expose a playable URL. Casting is exposed behind a platform service so unsupported platforms fail visibly instead of pretending a connection exists.

**Tech Stack:** Spring Boot 2.7 / Java 8, OkHttp, Jsoup, Caffeine, JUnit/MockMvc; Flutter/Dart, `http`, `video_player`, `url_launcher`, Provider, MethodChannel for platform route pickers.

---

### Task 1: Backend parser contract

**Files:**
- Create: `src/main/java/com/example/website/dto/video/VideoCategoryView.java`
- Create: `src/main/java/com/example/website/dto/video/VideoFilterView.java`
- Create: `src/main/java/com/example/website/dto/video/VideoItemView.java`
- Create: `src/main/java/com/example/website/dto/video/VideoSearchPageView.java`
- Create: `src/main/java/com/example/website/dto/video/VideoDetailView.java`
- Create: `src/main/java/com/example/website/dto/video/VideoPlayView.java`
- Create: `src/test/java/com/example/website/service/NcatVideoServiceTest.java`

- [ ] Write parser tests for category links, card metadata, tags, episode/line links, direct media URL extraction, and rejection of off-domain playback URLs.
- [ ] Run `mvn -q -Dtest=NcatVideoServiceTest test` and confirm the tests fail because the service and DTOs do not exist.
- [ ] Add immutable Lombok DTOs and implement package-private parser methods with fixture HTML only.
- [ ] Run the focused test again and confirm it passes.

### Task 2: ECS ncat21 adapter and API

**Files:**
- Modify: `src/main/java/com/example/website/config/AppProperties.java`
- Modify: `src/main/java/com/example/website/config/OkHttpConfig.java`
- Create: `src/main/java/com/example/website/service/NcatVideoService.java`
- Create: `src/main/java/com/example/website/controller/VideoController.java`
- Modify: `src/main/java/com/example/website/config/SecurityConfig.java`
- Create: `src/test/java/com/example/website/controller/VideoControllerTests.java`
- Modify: `src/test/resources/application.yml`

- [ ] Add a configurable `app.videoSources.ncat21BaseUrl` defaulting to `https://www.ncat21.com`, strict host validation, bounded redirects, 8-second call budget and Caffeine caches.
- [ ] Implement `GET /api/v1/videos/categories`, `/filters`, `/discover`, `/search`, `/detail`, and `/play`; normalize upstream outages to 502 with a stable error code and never return arbitrary proxy URLs.
- [ ] Require login for catalog calls and active membership for direct playback URL resolution, matching the existing media policy.
- [ ] Write controller/security tests for auth, parameter bounds, cache shape, upstream 502 behavior, and host validation.
- [ ] Run the focused controller/service tests, then `mvn -q test`.

### Task 3: Flutter video models and API

**Files:**
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/pubspec.yaml`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/lib/core/models/video.dart`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/lib/core/api/video_api.dart`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/test/video_api_test.dart`

- [ ] Add `video_player` with the existing `http` and URL launcher stack; keep the API base configurable for tests.
- [ ] Write client parsing/request tests using `ApiClient.forTesting` and a fake HTTP client.
- [ ] Implement typed category/filter/search/detail/play methods with pagination and upstream-unavailable messages.
- [ ] Run `flutter test test/video_api_test.dart` and `flutter analyze` on touched files.

### Task 4: DuckMusic video experience

**Files:**
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/lib/features/videos/videos_view.dart`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/lib/features/videos/video_detail_view.dart`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/lib/features/videos/video_player_view.dart`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/lib/core/services/video_history_service.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/lib/features/home/home_shell.dart`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/test/video_views_test.dart`

- [ ] Add a dedicated sidebar/bottom navigation entry without disturbing existing music indices.
- [ ] Render category tabs, filter chips/dropdowns, debounced search, paginated cards, detail metadata, line/episode selection and retry/empty states.
- [ ] Use `video_player` only for HTTPS `video/mp4`, HLS and DASH URLs returned by ECS; otherwise open the validated upstream playback page through `url_launcher`.
- [ ] Persist last-watched episode and position locally, keyed by source/video/episode, and restore it on detail/player open.
- [ ] Add widget tests for loading/error/empty, filters, episode selection and external-playback fallback.

### Task 5: Casting entry point

**Files:**
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/lib/core/services/cast_service.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/lib/features/videos/video_player_view.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/ios/Runner/AppDelegate.swift`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/macos/Runner/AppDelegate.swift`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/android/app/src/main/kotlin/com/music/player/MainActivity.kt`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/test/cast_service_test.dart`

- [ ] Define a platform-neutral cast state (`unsupported`, `idle`, `connecting`, `connected`, `failed`) and test it without platform calls.
- [ ] Implement Apple route-picker bridge where the platform shell supports it and make Android/Windows clearly report unsupported until a receiver SDK is configured; provide a validated “open on TV/share playback page” fallback.
- [ ] Add an accessible cast button with connection state and retry behavior; never claim success without a platform callback.

### Task 6: Verification and operational docs

**Files:**
- Modify: `docs/frontend-api.md`
- Modify: `docs/production-release.md`
- Modify: `README.md`

- [ ] Run backend unit/controller tests and full Maven suite.
- [ ] Run Flutter focused tests, full `flutter test`, and `flutter analyze`.
- [ ] Run a local mocked-upstream smoke test for categories/search/detail/play failure and an emulator/device smoke test for navigation/player fallback.
- [ ] Document required `APP_VIDEO_NCAT21_BASE_URL`, membership behavior, upstream limitations, and rollout/rollback checks.

