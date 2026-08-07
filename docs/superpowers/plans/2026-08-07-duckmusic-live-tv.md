# DuckMusic Tonkiang Live TV Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add classified, searchable Tonkiang television live streams to DuckMusic with native HLS playback, line failover, signed HTTPS casting URLs, full tests, and Spring Boot deployment to ECS.

**Architecture:** Spring Boot owns the built-in channel catalog, Tonkiang HTML adapter, bounded HLS probing, cached opaque line references, and a signed SSRF-safe HLS relay. DuckMusic calls only `/api/v1/live/**`, presents a dedicated live-TV interface, and feeds only ECS HTTPS relay URLs to `video_player` and the existing `CastService`; the old VOD implementation remains compatible but is hidden from navigation.

**Tech Stack:** Java 8, Spring Boot 2.7, Spring Security, OkHttp, Jsoup, Caffeine, MockWebServer, JUnit 5, Flutter/Dart, `http`, `video_player`, existing platform casting bridge, Nginx, systemd.

---

## File Map

Backend files to create:

- `src/main/java/com/example/website/dto/live/LiveCategoryView.java`: category response.
- `src/main/java/com/example/website/dto/live/LiveChannelView.java`: built-in channel response.
- `src/main/java/com/example/website/dto/live/LiveLineView.java`: safe line metadata without an upstream URL.
- `src/main/java/com/example/website/dto/live/LiveSearchView.java`: search identifier, pagination, and lines.
- `src/main/java/com/example/website/dto/live/LivePlayRequest.java`: opaque search/channel and line identifiers.
- `src/main/java/com/example/website/dto/live/LivePlaybackView.java`: signed HTTPS manifest URL and expiry.
- `src/main/java/com/example/website/service/live/LiveChannelCatalog.java`: static categories and channel keywords.
- `src/main/java/com/example/website/service/live/TonkiangLiveClient.java`: form request and Jsoup parsing only.
- `src/main/java/com/example/website/service/live/LiveUrlPolicy.java`: public HTTP(S) URL validation and pinned DNS.
- `src/main/java/com/example/website/service/live/LiveHlsProbe.java`: bounded manifest validation.
- `src/main/java/com/example/website/service/live/LiveSearchRateLimiter.java`: per-user uncached query budget.
- `src/main/java/com/example/website/service/live/LiveLineService.java`: cache, deduplication, probing, sorting, opaque IDs.
- `src/main/java/com/example/website/service/live/LiveRelayTokenService.java`: HMAC token signing and verification.
- `src/main/java/com/example/website/service/live/LiveHlsRelayService.java`: manifest rewriting and bounded upstream resource streaming.
- `src/main/java/com/example/website/controller/LiveTvController.java`: authenticated JSON endpoints.
- `src/main/java/com/example/website/controller/LiveRelayController.java`: signed unauthenticated media endpoints.
- `src/main/java/com/example/website/common/LiveTvException.java`: stable code and HTTP status.

Backend files to modify:

- `pom.xml`: add MockWebServer test dependency.
- `src/main/java/com/example/website/config/AppProperties.java`: add `liveTv` properties.
- `src/main/java/com/example/website/config/OkHttpConfig.java`: add dedicated no-redirect live client.
- `src/main/java/com/example/website/config/SecurityConfig.java`: authenticate JSON endpoints and permit only signed relay GETs.
- `src/main/java/com/example/website/common/GlobalExceptionHandler.java`: map `LiveTvException` status/code.
- `src/main/resources/application.yml`: production defaults and environment variables.
- `src/test/resources/application.yml`: deterministic test secret and limits.

Backend tests and fixtures to create:

- `src/test/resources/fixtures/tonkiang-cctv1.html`.
- `src/test/resources/fixtures/tonkiang-empty.html`.
- `src/test/java/com/example/website/service/live/LiveChannelCatalogTest.java`.
- `src/test/java/com/example/website/service/live/TonkiangLiveClientTest.java`.
- `src/test/java/com/example/website/service/live/LiveUrlPolicyTest.java`.
- `src/test/java/com/example/website/service/live/LiveHlsProbeTest.java`.
- `src/test/java/com/example/website/service/live/LiveLineServiceTest.java`.
- `src/test/java/com/example/website/service/live/LiveRelayTokenServiceTest.java`.
- `src/test/java/com/example/website/service/live/LiveHlsRelayServiceTest.java`.
- `src/test/java/com/example/website/controller/LiveTvControllerTest.java`.
- `src/test/java/com/example/website/LiveTvSecurityIntegrationTests.java`.
- `src/test/java/com/example/website/LiveTvConfigurationTests.java`.

DuckMusic files to create:

- `lib/core/models/live_tv.dart`: immutable live-TV models.
- `lib/core/api/live_tv_api.dart`: live API client.
- `lib/features/live_tv/live_line_failover.dart`: deterministic next-line state machine.
- `lib/features/live_tv/live_tv_view.dart`: categories, channels, search, and line selection.
- `lib/features/live_tv/live_player_view.dart`: native HLS player, manual/automatic line switching, casting.
- `test/live_tv_models_test.dart`.
- `test/live_tv_api_test.dart`.
- `test/live_line_failover_test.dart`.
- `test/live_tv_view_test.dart`.

DuckMusic files to modify:

- `lib/features/home/home_shell.dart`: replace the visible VOD tab with live TV.
- `lib/features/tools/tools_view.dart`: enable the television live tool and remove the visible VOD card.
- `test/widget_test.dart`: expect the new navigation label.
- `test/video_views_test.dart`: keep legacy VOD tests isolated from navigation expectations.

Deployment files to modify:

- `deploy/production/backend.env.example`: document live relay environment values.
- `deploy/production/nginx.conf`: add a dedicated relay location with streaming and rate limits.
- `docs/frontend-api.md`: document live API contracts and errors.
- `docs/production-release.md`: add backend-only release and live smoke checks.

## Task 1: Backend Contract, Configuration, and Channel Catalog

**Files:**

- Create the six DTO files under `src/main/java/com/example/website/dto/live/`.
- Create `src/main/java/com/example/website/service/live/LiveChannelCatalog.java`.
- Create `src/test/java/com/example/website/service/live/LiveChannelCatalogTest.java`.
- Modify `src/main/java/com/example/website/config/AppProperties.java:17-24,116-163`.
- Modify `src/main/resources/application.yml:62-131`.
- Modify `src/test/resources/application.yml:14-34`.

- [ ] **Step 1: Write the failing catalog and DTO tests**

```java
@Test
void exposesStableCategoriesAndChannelsWithoutMediaUrls() {
    LiveChannelCatalog catalog = new LiveChannelCatalog();
    assertEquals(Arrays.asList("featured", "cctv", "satellite", "local", "sports"),
            catalog.categories().stream().map(LiveCategoryView::getId).collect(Collectors.toList()));
    LiveChannelView cctv1 = catalog.channels("cctv").stream()
            .filter(channel -> channel.getId().equals("cctv-1"))
            .findFirst().orElseThrow(AssertionError::new);
    assertEquals("CCTV1", cctv1.getKeyword());
    assertFalse(cctv1.getName().contains("http"));
}
```

- [ ] **Step 2: Run the focused test and verify the missing-class failure**

Run: `mvn -Dtest=LiveChannelCatalogTest test`

Expected: compilation fails because `LiveChannelCatalog` and live DTOs do not exist.

- [ ] **Step 3: Add configuration and immutable response models**

Add `private final LiveTv liveTv = new LiveTv();` to `AppProperties` and define Java 8-compatible properties:

```java
@Getter
@Setter
public static class LiveTv {
    private String tonkiangBaseUrl = "https://tonkiang.us/";
    private String relayBaseUrl = "https://api.751152.xyz/api/v1/live/relay";
    private String relayHmacSecret;
    private int searchCacheSeconds = 300;
    private int emptyCacheSeconds = 60;
    private int relayTokenMinutes = 20;
    private int maxProbeLines = 6;
    private int manifestMaxBytes = 524288;
    private long resourceMaxBytes = 33554432L;
    private int searchRequestsPerMinute = 12;
}
```

Use Lombok `@Getter` and `@AllArgsConstructor` for DTOs. `LiveLineView` contains only `id`, `name`, `resolution`, `region`, `checkedAt`, `protocol`, and `available`. `LivePlaybackView` contains `channelName`, `lineId`, `mediaUrl`, `format`, and `expiresAt`.

- [ ] **Step 4: Implement the built-in catalog**

Use immutable lists containing CCTV-1 through CCTV-17, CCTV-5+, provincial satellite channels, and sports aliases. A channel constructor has exactly these fields:

```java
private static LiveChannelView channel(String id, String name, String keyword,
                                       String categoryId, String logoUrl) {
    return new LiveChannelView(id, name, keyword, categoryId, logoUrl);
}
```

Reject unknown categories with `new BusinessException(404, "直播分类不存在")` and return defensive unmodifiable lists.

- [ ] **Step 5: Add YAML defaults and run tests**

Add `app.live-tv.*` values backed by `APP_LIVE_*` variables. Test configuration uses `APP_LIVE_RELAY_HMAC_SECRET` equivalent value `test-live-relay-secret-at-least-32-bytes`.

Run: `mvn -Dtest=LiveChannelCatalogTest test`

Expected: PASS.

- [ ] **Step 6: Commit the catalog slice**

```bash
git add src/main/java/com/example/website/config/AppProperties.java src/main/resources/application.yml src/test/resources/application.yml src/main/java/com/example/website/dto/live src/main/java/com/example/website/service/live/LiveChannelCatalog.java src/test/java/com/example/website/service/live/LiveChannelCatalogTest.java
git commit -m "Add live TV catalog contract"
```

## Task 2: Tonkiang HTML Adapter

**Files:**

- Create `src/main/java/com/example/website/service/live/TonkiangLiveClient.java`.
- Create `src/test/java/com/example/website/service/live/TonkiangLiveClientTest.java`.
- Create both fixtures under `src/test/resources/fixtures/`.
- Modify `src/main/java/com/example/website/config/OkHttpConfig.java:17-23,106-118`.
- Modify `pom.xml:105-119` to add `com.squareup.okhttp3:mockwebserver` with test scope.

- [ ] **Step 1: Save sanitized real-result fixtures and write parser tests**

The fixture keeps Tonkiang's form and three representative result blocks but uses documentation IPs and example hosts. Test exact parsing:

```java
@Test
void parsesLinesAndPaginationFromTonkiangHtml() throws Exception {
    String html = fixture("fixtures/tonkiang-cctv1.html");
    TonkiangLiveClient.Page page = TonkiangLiveClient.parse(html, "CCTV1", 1);
    assertEquals(3, page.getLines().size());
    assertEquals("CCTV1", page.getLines().get(0).getChannelName());
    assertEquals("1920x1080", page.getLines().get(0).getResolution());
    assertEquals(68, page.getTotalPages());
    assertTrue(page.getLines().get(0).getMediaUrl().endsWith("index.m3u8"));
}
```

Also test empty results, malformed result blocks, non-HLS links, page clamping, and a missing pagination section.

- [ ] **Step 2: Verify tests fail**

Run: `mvn -Dtest=TonkiangLiveClientTest test`

Expected: compilation fails because the client does not exist.

- [ ] **Step 3: Add a dedicated Tonkiang OkHttp client**

Add `CLIENT_LIVE_TONKIANG` and a bean cloned from the primary client with 5-second connect, 8-second read, 10-second call timeout, no automatic redirects, and no retries.

- [ ] **Step 4: Implement POST and structured parsing**

The request body must be `new FormBody.Builder().add("seerch", normalizedKeyword).build()`. Set a browser-compatible user agent, `Accept: text/html`, and fixed `Origin`/`Referer` for Tonkiang. Parse with Jsoup selectors and derive pagination only from links whose query contains the same `chname`; never evaluate scripts.

The parser returns package-private immutable `Page` and `RawLine` values so fixture tests never need network access. Reject a response over 1 MiB before parsing.

- [ ] **Step 5: Run parser tests**

Run: `mvn -Dtest=TonkiangLiveClientTest test`

Expected: PASS with no external network access.

- [ ] **Step 6: Commit the adapter**

```bash
git add pom.xml src/main/java/com/example/website/config/OkHttpConfig.java src/main/java/com/example/website/service/live/TonkiangLiveClient.java src/test/java/com/example/website/service/live/TonkiangLiveClientTest.java src/test/resources/fixtures
git commit -m "Parse Tonkiang live TV results"
```

## Task 3: Public URL Policy, HLS Probe, Cache, and Rate Limit

**Files:**

- Create `LiveUrlPolicy.java`, `LiveHlsProbe.java`, `LiveSearchRateLimiter.java`, and `LiveLineService.java` under `service/live`.
- Create their four focused test files.
- Create `src/main/java/com/example/website/common/LiveTvException.java`.
- Modify `OkHttpConfig.java` with `CLIENT_LIVE_MEDIA` using the policy DNS implementation.

- [ ] **Step 1: Write URL-policy tests for every forbidden address class**

```java
@ParameterizedTest
@ValueSource(strings = {
        "http://127.0.0.1/a.m3u8", "http://169.254.169.254/latest/meta-data/",
        "http://10.0.0.1/a.m3u8", "http://192.168.1.1/a.m3u8",
        "http://[::1]/a.m3u8", "file:///etc/passwd"
})
void rejectsNonPublicTargets(String value) {
    assertThrows(LiveTvException.class, () -> policy.requirePublicHttpUrl(value));
}
```

Inject a resolver in tests so public and private DNS results are deterministic. Test that one private answer among multiple answers rejects the hostname.

- [ ] **Step 2: Write probe and line-service tests**

Use MockWebServer for `#EXTM3U`, HTML masquerading as media, oversized manifests, timeout, duplicate URL normalization, HTTPS-first sorting, max-six probes, five-minute success cache, one-minute empty cache, and opaque IDs that do not contain the URL.

- [ ] **Step 3: Run tests and confirm failures**

Run: `mvn -Dtest=LiveUrlPolicyTest,LiveHlsProbeTest,LiveLineServiceTest test`

Expected: compilation fails because the services do not exist.

- [ ] **Step 4: Implement policy and pinned DNS**

`LiveUrlPolicy` accepts only `http`/`https`, no user info, non-empty host, URL length at most 4096, and validates every `InetAddress`. Its `Dns` implementation returns only the already validated address list to OkHttp, eliminating a second uncontrolled DNS lookup.

- [ ] **Step 5: Implement bounded probing and cached opaque line references**

Probe with a GET carrying `Range: bytes=0-524287`; accept only a body beginning with `#EXTM3U`. `LiveLineService` normalizes the keyword to 1-40 characters, calls the rate limiter only on a cache miss, probes at most `maxProbeLines` concurrently on a bounded executor, and stores the upstream URL only inside a Caffeine cache entry.

Generate `searchId` and `lineId` from random 128-bit URL-safe values. `resolve(searchId, lineId, userId)` confirms the cached owner and returns the internal URL; otherwise throw `LIVE_LINE_EXPIRED` with HTTP 410.

- [ ] **Step 6: Run focused tests and commit**

Run: `mvn -Dtest=LiveUrlPolicyTest,LiveHlsProbeTest,LiveLineServiceTest test`

Expected: PASS.

```bash
git add src/main/java/com/example/website/common/LiveTvException.java src/main/java/com/example/website/config/OkHttpConfig.java src/main/java/com/example/website/service/live/LiveUrlPolicy.java src/main/java/com/example/website/service/live/LiveHlsProbe.java src/main/java/com/example/website/service/live/LiveSearchRateLimiter.java src/main/java/com/example/website/service/live/LiveLineService.java src/test/java/com/example/website/service/live
git commit -m "Validate and rank live HLS lines"
```

## Task 4: Signed Relay Tokens and Manifest Rewriting

**Files:**

- Create `LiveRelayTokenService.java` and `LiveHlsRelayService.java`.
- Create `LiveRelayTokenServiceTest.java` and `LiveHlsRelayServiceTest.java`.

- [ ] **Step 1: Write token tests**

Test round-trip payload fields `url`, `kind`, `userId`, and `expiresAt`; one-byte signature tampering; expired token; wrong resource kind; malformed Base64; and a secret shorter than 32 bytes.

```java
String token = service.sign("http://media.example/live/index.m3u8", ResourceKind.MANIFEST, 7L, now.plusSeconds(60));
RelayTarget target = service.verify(token, ResourceKind.MANIFEST, now);
assertEquals("http://media.example/live/index.m3u8", target.getUrl());
assertEquals(Long.valueOf(7L), target.getUserId());
```

- [ ] **Step 2: Write manifest-rewrite tests**

Use MockWebServer responses containing relative variant playlists, absolute segments, `#EXT-X-KEY:URI`, `#EXT-X-MAP:URI`, query strings, and CRLF. Assert every URI becomes `https://api.751152.xyz/api/v1/live/relay/{token}/manifest.m3u8` or `/resource`, while comments and durations remain unchanged.

- [ ] **Step 3: Verify failures**

Run: `mvn -Dtest=LiveRelayTokenServiceTest,LiveHlsRelayServiceTest test`

Expected: compilation fails because relay services do not exist.

- [ ] **Step 4: Implement HMAC tokens**

Encode a compact Jackson JSON payload with URL, enum kind, user ID, and epoch-second expiry. Sign the Base64URL payload with `HmacSHA256`; verify with `MessageDigest.isEqual`; reject tokens over 8192 characters and expiry beyond the configured maximum lifetime.

- [ ] **Step 5: Implement bounded manifest fetching and rewriting**

Manually process redirects up to three hops, validating each target through `LiveUrlPolicy`. Read at most `manifestMaxBytes + 1`, require `#EXTM3U`, resolve relative URIs against the current response URL, and sign each nested target. Preserve no upstream `Set-Cookie`, CORS, server, or hop-by-hop headers.

- [ ] **Step 6: Run tests and commit**

Run: `mvn -Dtest=LiveRelayTokenServiceTest,LiveHlsRelayServiceTest test`

Expected: PASS.

```bash
git add src/main/java/com/example/website/service/live/LiveRelayTokenService.java src/main/java/com/example/website/service/live/LiveHlsRelayService.java src/test/java/com/example/website/service/live/LiveRelayTokenServiceTest.java src/test/java/com/example/website/service/live/LiveHlsRelayServiceTest.java
git commit -m "Add signed HLS relay core"
```

## Task 5: Live JSON API and Streaming Relay Controller

**Files:**

- Create `LiveTvController.java` and `LiveRelayController.java`.
- Create `LiveTvControllerTest.java` and `LiveTvSecurityIntegrationTests.java`.
- Modify `SecurityConfig.java:47-82` and `GlobalExceptionHandler.java:32-57`.

- [ ] **Step 1: Write controller contract tests**

Cover categories, category channels, custom search, channel lines, play, invalid keyword, missing user ID, expired line, and signed relay response headers. Expected JSON shape:

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "searchId": "opaque-search-id",
    "keyword": "CCTV1",
    "page": 1,
    "totalPages": 68,
    "lines": [{"id":"opaque-line-id","name":"线路 1","resolution":"1920x1080","protocol":"http","available":true}]
  }
}
```

- [ ] **Step 2: Write security integration tests**

Assert `/api/v1/live/categories` returns 401 without JWT, a valid signed `/api/v1/live/relay/{token}/manifest.m3u8` reaches the relay without JWT, and tampered/expired relay tokens return 401/410 without JSON stack details.

- [ ] **Step 3: Verify failures**

Run: `mvn -Dtest=LiveTvControllerTest,LiveTvSecurityIntegrationTests test`

Expected: endpoints are missing or denied.

- [ ] **Step 4: Implement controllers and error mapping**

`LiveTvController` obtains `userId` from the request attribute and never accepts an upstream URL. `POST /play` resolves an opaque cached line, signs a manifest token, and returns a URL built from `relayBaseUrl`.

`LiveRelayController` returns rewritten manifests as `application/vnd.apple.mpegurl; charset=UTF-8`. Resource responses use `StreamingResponseBody`, forward only `Content-Type`, `Content-Length`, `Content-Range`, `Accept-Ranges`, `ETag`, and `Last-Modified`, and enforce `resourceMaxBytes` while copying. Forward a syntactically valid single `Range` header only.

Map stable codes to HTTP: upstream unavailable 502, rate limit 429, missing line 404, expired line/token 410, invalid token 401, rejected URL 400.

- [ ] **Step 5: Configure Spring Security ordering**

Place `antMatchers(HttpMethod.GET, "/api/v1/live/relay/**").permitAll()` before `antMatchers("/api/v1/live/**").authenticated()`. Include `/api/v1/live/` in the Chinese authentication message condition.

- [ ] **Step 6: Run controller tests and the complete backend suite**

Run: `mvn -Dtest=LiveTvControllerTest,LiveTvSecurityIntegrationTests test`

Expected: PASS.

Run: `mvn test`

Expected: BUILD SUCCESS with all existing authentication, music, book, membership, and video tests passing.

- [ ] **Step 7: Commit the HTTP surface**

```bash
git add src/main/java/com/example/website/controller/LiveTvController.java src/main/java/com/example/website/controller/LiveRelayController.java src/main/java/com/example/website/config/SecurityConfig.java src/main/java/com/example/website/common/GlobalExceptionHandler.java src/test/java/com/example/website/controller/LiveTvControllerTest.java src/test/java/com/example/website/LiveTvSecurityIntegrationTests.java
git commit -m "Expose live TV and signed relay APIs"
```

## Task 6: DuckMusic Live Models and API

**Files:**

- Create `lib/core/models/live_tv.dart` and `lib/core/api/live_tv_api.dart`.
- Create `test/live_tv_models_test.dart` and `test/live_tv_api_test.dart`.

- [ ] **Step 1: Write model and HTTP contract tests**

```dart
test('parses live search without exposing upstream URLs', () {
  final result = LiveSearchResult.fromJson({
    'searchId': 'search-1',
    'keyword': 'CCTV1',
    'page': 1,
    'totalPages': 68,
    'lines': [
      {'id': 'line-1', 'name': '线路 1', 'resolution': '1920x1080', 'protocol': 'http', 'available': true}
    ],
  });
  expect(result.lines.single.id, 'line-1');
  expect(result.toString(), isNot(contains('m3u8')));
});
```

The API test must assert paths, encoded Chinese keywords, `page`, JSON play body, and HTTPS playback parsing.

- [ ] **Step 2: Verify failures**

Run: `flutter test test/live_tv_models_test.dart test/live_tv_api_test.dart`

Expected: compilation fails because the files do not exist.

- [ ] **Step 3: Implement immutable Dart models**

Define `LiveCategory`, `LiveChannel`, `LiveLine`, `LiveSearchResult`, and `LivePlayback`. Normalize absent lists to empty, integers from numeric/string JSON, and optional metadata to empty display strings. `LivePlayback.isPlayable` is true only for an HTTPS URL with a non-empty host and `format == 'hls'`.

- [ ] **Step 4: Implement `LiveTvApi`**

Methods are `categories()`, `channels(category)`, `search(keyword, page)`, `lines(channelId)`, and `play(referenceId, lineId)`. Reuse `ApiClient`; never accept or return a raw upstream URL field.

- [ ] **Step 5: Run tests and commit**

Run: `flutter test test/live_tv_models_test.dart test/live_tv_api_test.dart`

Expected: PASS.

```bash
git add lib/core/models/live_tv.dart lib/core/api/live_tv_api.dart test/live_tv_models_test.dart test/live_tv_api_test.dart
git commit -m "Add DuckMusic live TV data layer"
```

## Task 7: Live Catalog, Classification, and Search UI

**Files:**

- Create `lib/features/live_tv/live_tv_view.dart` and `test/live_tv_view_test.dart`.
- Modify `lib/features/home/home_shell.dart:24,38-39,59-87,329-330,826-832`.
- Modify `lib/features/tools/tools_view.dart:4-8,23-66`.
- Modify navigation expectations in `test/widget_test.dart`.

- [ ] **Step 1: Write widget tests with a fake API**

Cover loading, category selection, channel selection, debounced search submit, empty results, upstream error, retry, and navigation into the player. Use keys `live-search-field`, `live-category-{id}`, `live-channel-{id}`, and `live-line-{id}` for stable assertions.

- [ ] **Step 2: Run the widget test and verify failure**

Run: `flutter test test/live_tv_view_test.dart`

Expected: compilation fails because `LiveTvView` does not exist.

- [ ] **Step 3: Implement the live page**

Use a `CustomScrollView` with the existing `GPageHeader`, a search field, horizontal filter chips, and compact `ListTile`/grid channel rows. Keep stable dimensions for logos and line status. Do not nest cards or reuse poster-oriented VOD components.

Cancel stale requests by incrementing a request generation integer and applying a result only when its captured generation still matches. A channel tap loads its lines; a search submit calls `search`; both display a bottom sheet with available lines and a clear retry action.

- [ ] **Step 4: Replace visible VOD navigation**

Import `../live_tv/live_tv_view.dart`, rename `_videoIndex` to `_liveTvIndex`, change both labels to `直播`, and return `const LiveTvView(key: ValueKey('live-tv'))`. Keep old VOD files untouched. Enable the tools-page television card with `page: LiveTvView()` and remove the visible “电视点播” entry.

- [ ] **Step 5: Run UI tests and commit**

Run: `flutter test test/live_tv_view_test.dart test/widget_test.dart test/video_views_test.dart`

Expected: PASS; legacy VOD tests still pass when instantiated directly, while app navigation shows `直播` and not `影视`.

```bash
git add lib/features/live_tv/live_tv_view.dart lib/features/home/home_shell.dart lib/features/tools/tools_view.dart test/live_tv_view_test.dart test/widget_test.dart test/video_views_test.dart
git commit -m "Add live TV catalog and search UI"
```

## Task 8: HLS Playback, Automatic Failover, and Casting

**Files:**

- Create `live_line_failover.dart`, `live_player_view.dart`, and `live_line_failover_test.dart`.
- Extend `live_tv_view_test.dart` for player navigation and manual line choice.
- Reuse `lib/core/services/cast_service.dart` without widening its HTTPS validation.

- [ ] **Step 1: Write pure failover tests**

```dart
test('tries each line once and stops', () {
  final failover = LiveLineFailover(['a', 'b', 'c']);
  expect(failover.start(), 'a');
  expect(failover.failCurrent(), 'b');
  expect(failover.failCurrent(), 'c');
  expect(failover.failCurrent(), isNull);
  expect(failover.failCurrent(), isNull);
});
```

Also test manual selection resets the current playback attempt but does not create a retry loop, and a refreshed line list creates a fresh session.

- [ ] **Step 2: Verify failure**

Run: `flutter test test/live_line_failover_test.dart`

Expected: compilation fails because the failover class does not exist.

- [ ] **Step 3: Implement the state machine and player lifecycle**

`LivePlayerView` receives the channel/search reference, channel name, initial lines, API, and optional `CastService`. For each selected line it calls `play`, verifies `isPlayable`, disposes the previous `VideoPlayerController`, initializes the new network controller, attaches an error listener, and starts playback.

Guard every async completion with both `mounted` and a monotonically increasing playback generation. On initialization or runtime error call `failCurrent()` once. After exhaustion show `所有线路暂时不可用` with `重新搜索线路`; do not open an arbitrary browser fallback.

- [ ] **Step 4: Add line and cast controls**

Use a menu button with `Icons.route` for manual line selection and an icon-only `Icons.cast` action with tooltip `投屏`. Start casting with the current `LivePlayback.mediaUrl`; when native casting reports `pauseLocal`, pause local playback. Stop casting and dispose the video controller on view disposal.

- [ ] **Step 5: Run focused and full Flutter checks**

Run: `flutter test test/live_line_failover_test.dart test/live_tv_view_test.dart test/cast_service_test.dart`

Expected: PASS.

Run: `dart format --output=none --set-exit-if-changed lib test`

Expected: no formatting changes required after the explicit format pass.

Run: `flutter analyze`

Expected: no issues.

Run: `flutter test`

Expected: all tests pass.

- [ ] **Step 6: Commit playback**

```bash
git add lib/features/live_tv/live_line_failover.dart lib/features/live_tv/live_player_view.dart lib/features/live_tv/live_tv_view.dart test/live_line_failover_test.dart test/live_tv_view_test.dart
git commit -m "Play and cast live TV with failover"
```

## Task 9: Production Configuration and Documentation

**Files:**

- Modify `deploy/production/backend.env.example`, `deploy/production/nginx.conf`, `docs/frontend-api.md`, and `docs/production-release.md`.

- [ ] **Step 1: Add configuration validation tests or startup checks**

Add a Spring test that starts with a 31-byte relay secret and expects context validation failure, then starts with the test secret and succeeds. Production must not fall back to the JWT secret.

- [ ] **Step 2: Add environment variables**

Document these exact names without real secrets:

```dotenv
APP_LIVE_TONKIANG_BASE_URL=https://tonkiang.us/
APP_LIVE_RELAY_BASE_URL=https://api.751152.xyz/api/v1/live/relay
APP_LIVE_RELAY_HMAC_SECRET=replace-with-a-separate-random-secret
APP_LIVE_SEARCH_CACHE_SECONDS=300
APP_LIVE_EMPTY_CACHE_SECONDS=60
APP_LIVE_RELAY_TOKEN_MINUTES=20
APP_LIVE_MAX_PROBE_LINES=6
APP_LIVE_MANIFEST_MAX_BYTES=524288
APP_LIVE_RESOURCE_MAX_BYTES=33554432
APP_LIVE_SEARCH_REQUESTS_PER_MINUTE=12
```

- [ ] **Step 3: Add Nginx relay policy**

Define `limit_conn_zone` and `limit_req_zone` at HTTP scope in the installed production configuration. Add a location before `/`:

```nginx
location ^~ /api/v1/live/relay/ {
    limit_conn live_relay_conn 4;
    limit_req zone=live_relay_req burst=20 nodelay;
    proxy_pass http://127.0.0.1:8090;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header Range $http_range;
    proxy_buffering off;
    proxy_request_buffering off;
    proxy_read_timeout 30s;
    proxy_send_timeout 30s;
}
```

Document that the two `*_zone` declarations must be added to `http {}` rather than copied inside `server {}`.

- [ ] **Step 4: Document API and production smoke workflow**

Add request/response examples for categories, channels, search, play, manifest, expiry refresh, and stable errors. Add exact smoke commands using `https://api.751152.xyz` and an authenticated token supplied at execution time through `DUCKMUSIC_ACCESS_TOKEN`, never written to shell history or Git.

- [ ] **Step 5: Validate configuration and commit**

Run: `mvn test`

Expected: BUILD SUCCESS.

Run: `git diff --check`

Expected: no whitespace errors.

```bash
git add deploy/production/backend.env.example deploy/production/nginx.conf docs/frontend-api.md docs/production-release.md src/test/java/com/example/website/LiveTvConfigurationTests.java
git commit -m "Document and constrain live relay deployment"
```

## Task 10: Real-Line Verification, ECS Deployment, and Final Regression

**Files:**

- No new application files expected; record any reproducible operational clarification in `docs/production-release.md`.

- [ ] **Step 1: Run final local verification from clean feature diffs**

Backend:

```bash
mvn test
mvn -DskipTests package
```

DuckMusic:

```bash
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
```

Expected: all commands exit 0. Review `git diff --check` and `git status --short` in both repositories; distinguish task files from pre-existing user changes.

- [ ] **Step 2: Test one real Tonkiang search without bulk crawling**

Call one CCTV keyword and one satellite keyword through the local authenticated API. Confirm each response has at least one probed line or produces the documented retryable upstream error. Do not crawl pagination or enumerate all channels.

- [ ] **Step 3: Test the relay chain locally**

Request play for one HTTP HLS line, then request the returned manifest. Confirm the manifest begins with `#EXTM3U`, contains only ECS HTTPS resource URLs, a child resource accepts a valid Range request, and a tampered/expired token is rejected.

- [ ] **Step 4: Prepare a backend-only release while preserving the active web frontend**

Build `target/website-0.0.1-SNAPSHOT.jar`. Set `DUCKMUSIC_RELEASE_ID="20260807T-live-tv-java-$(date -u +%H%M%S)"`. Over SSH to `root@api.751152.xyz`, create `/opt/website/releases/$DUCKMUSIC_RELEASE_ID`, copy the active release tree with `cp -a /opt/website/current/. /opt/website/releases/$DUCKMUSIC_RELEASE_ID/`, replace only `backend/website.jar` and changed `ops` files, and leave `frontend/` byte-identical to the active release.

Before activation, append a separately generated 48-byte Base64 `APP_LIVE_RELAY_HMAC_SECRET` to `/opt/website/backend/.env` with mode 600. Never print the value in logs or commit it.

- [ ] **Step 5: Validate and activate production configuration**

Run `nginx -t` before reload. Activate with:

```bash
bash "/opt/website/releases/$DUCKMUSIC_RELEASE_ID/ops/remote-activate.sh" "$DUCKMUSIC_RELEASE_ID"
```

Expected: backend and frontend health checks pass; `current` points to the new release and `previous` points to the former active release. If activation or Nginx validation fails, restore the previous Nginx config and run `/opt/website/current/ops/remote-rollback.sh previous`.

- [ ] **Step 6: Run production smoke tests**

With `DUCKMUSIC_ACCESS_TOKEN` present only in the current shell, verify health, categories, CCTV1 search, play, manifest, one child resource, tampered token, and unauthenticated JSON rejection. Confirm the public relay URL is HTTPS and contains no raw upstream URL.

- [ ] **Step 7: Verify 60-second playback and casting behavior**

Play one real channel continuously for 60 seconds in DuckMusic. Force the first line to fail and confirm one automatic switch with no loop. On an available Apple device, start AirPlay and confirm the receiving device requests the ECS HTTPS manifest; on unsupported platforms, confirm the existing native unsupported message remains accurate.

- [ ] **Step 8: Observe and close**

Inspect `journalctl -u website-api -n 300 --no-pager` for 15 minutes of the smoke window. Confirm no raw signed tokens, media query credentials, stack traces, SSRF rejection internals, or sustained 5xx bursts are logged. Record the active release ID and final test totals in the handoff.

## Plan Self-Review Results

- Spec coverage: classification, search, line selection, probing, playback, failover, casting, SSRF controls, signed relay, caching, rate limits, tests, ECS deployment, and rollback each map to a task.
- Scope: VOD is hidden but not deleted; no TVBox, alternate M3U source, EPG, recording, or playback history work is included.
- Type consistency: backend uses `searchId`, `lineId`, `mediaUrl`, `expiresAt`; Flutter models and API use the same names.
- Security consistency: only the signed relay is unauthenticated; JSON APIs require JWT; no endpoint accepts an upstream media URL.
