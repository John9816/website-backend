# DuckMusic Danbo Direct Live TV Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproduce Danbo's runtime M3U catalog, direct multi-line playback, and failure fallback in DuckMusic on Android, iOS, macOS, and Windows while removing the Spring Boot live API and relay.

**Architecture:** Flutter directly fetches, parses, caches, groups, searches, and plays the current `interface.txt` M3U. `media_kit` provides one MPV/FFmpeg-based player abstraction on all four platforms; Spring Boot has no live-TV responsibility after the change.

**Tech Stack:** Flutter/Dart, `http`, `shared_preferences`, `media_kit`, `media_kit_video`, platform `media_kit_libs_*_video`, Flutter widget tests, Java 8, Spring Boot 2.7, Maven.

---

### Task 1: M3U Models And Parser

**Files:**
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/lib/core/models/live_tv.dart`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/lib/core/services/live_m3u_parser.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/test/live_tv_models_test.dart`
- Create: `/Users/wangweijie/projects/xcode/DuckMusic/test/fixtures/danbo-interface.txt`

- [ ] **Step 1: Write failing parser tests**

Test a small inline M3U with a BOM, header metadata, two ordered groups, duplicate channel entries, fallback names, and a malformed entry. Assert that duplicate entries remain distinct numbered `LiveLine` values.

```dart
final catalog = const LiveM3uParser().parse(source);
expect(catalog.groups.map((group) => group.name), ['央视频道', '卫视频道']);
expect(catalog.groups.first.channels.single.lines, hasLength(2));
expect(catalog.epgUrls, ['https://example.test/epg.xml']);
```

Load `test/fixtures/danbo-interface.txt` and assert 14 groups, 261 channels, 352 lines, CCTV1 with 7 lines, and CCTV5 with 3 lines.

- [ ] **Step 2: Verify RED**

Run: `flutter test test/live_tv_models_test.dart`

Expected: failure because `LiveM3uParser` and the new catalog model do not exist.

- [ ] **Step 3: Implement the immutable model and parser**

Define `LiveTvCatalog`, `LiveChannelGroup`, `LiveChannel`, and `LiveLine`. Parse attributes with a quoted-attribute regular expression, preserve insertion order with Dart maps, accept only absolute HTTP/HTTPS playback URIs, and throw `FormatException` when no playable channels remain.

- [ ] **Step 4: Verify GREEN**

Run: `flutter test test/live_tv_models_test.dart`

Expected: all parser and fixture assertions pass.

### Task 2: Direct Fetch And Last-Known-Good Cache

**Files:**
- Replace: `/Users/wangweijie/projects/xcode/DuckMusic/lib/core/api/live_tv_api.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/lib/core/config/app_config.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/test/live_tv_api_test.dart`

- [ ] **Step 1: Write failing repository tests**

Use injected `http.Client`, parser, and cache store fakes to verify the exact source URL, UTF-8 parsing, successful cache writes, cache reads, timeout/non-200 errors, and preservation of cached raw M3U after a failed refresh.

```dart
expect(request.url, Uri.parse(AppConfig.liveTvM3uUrl));
expect(await repository.readCached(), isNotNull);
await expectLater(repository.refresh(), throwsA(isA<LiveTvLoadException>()));
expect(cache.rawM3u, previousRawM3u);
```

- [ ] **Step 2: Verify RED**

Run: `flutter test test/live_tv_api_test.dart`

Expected: failure because the direct catalog repository contract is missing.

- [ ] **Step 3: Implement direct fetch and cache**

Keep `LiveTvApi` as the injectable repository name to minimize navigation churn. Add `readCached()` and `refresh()`, use `http.Client.get`, a 15-second timeout, `utf8.decode(..., allowMalformed: true)`, and a `SharedPreferences` raw-string cache store. Write the cache only after successful parsing.

- [ ] **Step 4: Verify GREEN**

Run: `flutter test test/live_tv_api_test.dart`

Expected: all direct-fetch and cache tests pass.

### Task 3: Cache-First Television Browser

**Files:**
- Replace: `/Users/wangweijie/projects/xcode/DuckMusic/lib/features/live_tv/live_tv_view.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/test/live_tv_view_test.dart`

- [ ] **Step 1: Write failing widget tests**

Assert cached content appears before a pending refresh, the header reports group/channel/line counts, group selection preserves order, search filters locally, a refresh error leaves cached channels visible with a stale notice, and a channel tile opens the player with all source lines.

- [ ] **Step 2: Verify RED**

Run: `flutter test test/live_tv_view_test.dart`

Expected: failures because the current widget calls backend catalog/search endpoints.

- [ ] **Step 3: Implement the browser**

Load cache, then refresh in the background. Store one `LiveTvCatalog`, derive selected-group and searched channels locally, show `CachedNetworkImage` logos with an icon fallback, show line counts, and navigate directly to `LivePlayerView(channel: channel)`.

- [ ] **Step 4: Verify GREEN**

Run: `flutter test test/live_tv_view_test.dart`

Expected: all cache-first browser tests pass.

### Task 4: Four-Platform media_kit Player And Failover

**Files:**
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/pubspec.yaml`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/pubspec.lock`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/lib/main.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/lib/features/live_tv/live_line_failover.dart`
- Replace: `/Users/wangweijie/projects/xcode/DuckMusic/lib/features/live_tv/live_player_view.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/test/live_line_failover_test.dart`
- Modify: `/Users/wangweijie/projects/xcode/DuckMusic/test/live_player_view_test.dart`
- Update generated plugin files only through Flutter tooling.

- [ ] **Step 1: Write failing failover and player tests**

Assert initial ordering from any selected line, one automatic attempt per line, late superseded failures ignored, direct HTTP and HTTPS URIs passed unchanged to the media adapter, manual selection resets the cycle, and exhausted retry begins a fresh cycle.

- [ ] **Step 2: Verify RED**

Run: `flutter test test/live_line_failover_test.dart test/live_player_view_test.dart`

Expected: failures because the existing player calls `LiveTvApi.play` and accepts only relay playback objects.

- [ ] **Step 3: Add media_kit dependencies and initialization**

Add `media_kit`, `media_kit_video`, `media_kit_libs_android_video`, `media_kit_libs_ios_video`, `media_kit_libs_macos_video`, and `media_kit_libs_windows_video`; run `flutter pub get`. Call `MediaKit.ensureInitialized()` in `main()` before `runApp`.

- [ ] **Step 4: Implement the direct player**

Create a small injectable media-session interface backed by `Player` and `VideoController`. Open `Media(line.uri.toString())`, listen to error and playing streams, use a generation counter to discard stale callbacks, and retain stable 16:9 layout while loading or switching. Provide play/pause, mute, fullscreen, line menu, and retry controls.

- [ ] **Step 5: Verify GREEN**

Run: `flutter test test/live_line_failover_test.dart test/live_player_view_test.dart`

Expected: all direct playback and failover tests pass.

### Task 5: Remove Backend Live Stack

**Files:**
- Delete: `/Users/wangweijie/projects/idea/website-backend/src/main/java/com/example/website/controller/LiveTvController.java`
- Delete: `/Users/wangweijie/projects/idea/website-backend/src/main/java/com/example/website/controller/LiveRelayController.java`
- Delete: `/Users/wangweijie/projects/idea/website-backend/src/main/java/com/example/website/common/LiveTvException.java`
- Delete: `/Users/wangweijie/projects/idea/website-backend/src/main/java/com/example/website/dto/live/`
- Delete: `/Users/wangweijie/projects/idea/website-backend/src/main/java/com/example/website/service/live/`
- Delete: all live-only tests and Tonkiang fixtures under `/Users/wangweijie/projects/idea/website-backend/src/test/`
- Modify: `/Users/wangweijie/projects/idea/website-backend/src/main/java/com/example/website/config/AppProperties.java`
- Modify: `/Users/wangweijie/projects/idea/website-backend/src/main/java/com/example/website/config/OkHttpConfig.java`
- Modify: `/Users/wangweijie/projects/idea/website-backend/src/main/java/com/example/website/config/SecurityConfig.java`
- Modify: `/Users/wangweijie/projects/idea/website-backend/src/main/java/com/example/website/common/GlobalExceptionHandler.java`
- Modify: `/Users/wangweijie/projects/idea/website-backend/src/main/resources/application.yml`
- Modify: `/Users/wangweijie/projects/idea/website-backend/src/test/resources/application.yml`

- [ ] **Step 1: Capture the live-only reference set**

Run: `rg -n 'LiveTv|LiveRelay|service\\.live|dto\\.live|app\\.live-tv|/api/v1/live' src pom.xml docs`

Expected: references resolve only to the live stack and shared-file snippets listed above.

- [ ] **Step 2: Remove live-only files and shared snippets**

Use patch-based deletion. Preserve the separate music/video/books authentication message and all unrelated OkHttp clients and configuration blocks.

- [ ] **Step 3: Verify backend compilation and absence**

Run: `rg -n 'LiveTv|LiveRelay|service\\.live|dto\\.live|app\\.live-tv|/api/v1/live' src/main src/test`

Expected: no matches.

Run: `mvn test`

Expected: build succeeds with zero test failures.

### Task 6: Full Flutter And Platform Verification

**Files:**
- Modify only when a failing verifier proves a required platform integration change.

- [ ] **Step 1: Format and analyze**

Run: `dart format lib/core/models/live_tv.dart lib/core/api/live_tv_api.dart lib/core/services/live_m3u_parser.dart lib/features/live_tv test/live_tv_*_test.dart`

Run: `flutter analyze`

Expected: no analyzer errors.

- [ ] **Step 2: Run the complete Flutter suite**

Run: `flutter test`

Expected: all tests pass.

- [ ] **Step 3: Build locally available targets**

Run on macOS: `flutter build macos --debug`, `flutter build ios --debug --no-codesign`, and `flutter build apk --debug` when the Android SDK is available.

Run or inspect Windows CI configuration to prove `media_kit_libs_windows_video` registration; a Windows binary cannot be produced on macOS and must be reported as such.

- [ ] **Step 4: Current-source smoke check**

Fetch and parse the current source without persisting credentials. Confirm the live counts are non-zero and open at least one current line in an available local application build. Record any upstream-expiry or device limitations explicitly.

### Task 7: Remove Obsolete Live Documentation

**Files:**
- Delete or replace only documents that exclusively describe the removed Tonkiang/ZBDS backend relay.
- Keep: `/Users/wangweijie/projects/idea/website-backend/docs/superpowers/specs/2026-08-07-duckmusic-danbo-direct-live-tv-design.md`
- Keep: `/Users/wangweijie/projects/idea/website-backend/docs/superpowers/plans/2026-08-07-duckmusic-danbo-direct-live-tv.md`

- [ ] **Step 1: Search documentation and deployment files**

Run: `rg -n 'Tonkiang|ZBDS|APP_LIVE_|/api/v1/live|live relay' README.md docs deploy`

- [ ] **Step 2: Remove obsolete live-only documents and snippets**

Preserve unrelated release, video, music, and deployment instructions.

- [ ] **Step 3: Review both repository diffs**

Run `git diff --stat` and targeted `git diff --` commands in both repositories. Confirm that all changed shared files retain the user's unrelated edits.
