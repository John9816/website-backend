# ChKSz NetEase Seven-Quality Resolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ChKSz the first NetEase playback resolver while accepting all seven NetEase quality values and preserving the existing TuneFree and cross-source fallbacks.

**Architecture:** Parse playback quality with source awareness, keeping the existing four-value `MusicQuality` as the TuneFree compatibility model. A focused `ChkszNeteaseMusicClient` owns authenticated HTTP calls, response mapping, negative caching, cooldowns, and circuit breaking; `MusicService` invokes it only for NetEase before the existing primary path.

**Tech Stack:** Java 8, Spring Boot 2.7, OkHttp, Jackson, Caffeine, JUnit 5, Mockito

---

### Task 1: Source-Aware Quality Model

**Files:**
- Create: `src/main/java/com/example/website/dto/music/NeteasePlaybackQuality.java`
- Create: `src/main/java/com/example/website/service/music/PlaybackQualityRequest.java`
- Test: `src/test/java/com/example/website/service/music/PlaybackQualityRequestTests.java`

- [ ] **Step 1: Write failing parsing and mapping tests**

Cover all seven NetEase values, the four legacy aliases, source-specific defaults, and rejection of NetEase-only values for QQ/Kuwo. Assert that `jymaster` maps to `MusicQuality.FLAC24`, `flac` aliases `lossless`, and an omitted NetEase quality defaults to `lossless`.

- [ ] **Step 2: Run the focused test and verify failure**

Run: `./mvnw -q -Dtest=PlaybackQualityRequestTests test` if `mvnw` exists, otherwise `mvn -q -Dtest=PlaybackQualityRequestTests test`.

Expected: compilation fails because `PlaybackQualityRequest` and `NeteasePlaybackQuality` do not exist.

- [ ] **Step 3: Implement the minimal models**

`NeteasePlaybackQuality` defines `standard`, `exhigh`, `lossless`, `hires`, `jyeffect`, `sky`, and `jymaster`, each with a `MusicQuality` fallback. `PlaybackQualityRequest.parse(source, raw)` stores the caller-facing value, canonical cache value, optional NetEase value, and legacy fallback value.

- [ ] **Step 4: Run focused tests**

Run: `mvn -q -Dtest=PlaybackQualityRequestTests test`.

Expected: all quality model tests pass.

### Task 2: ChKSz Configuration and Client

**Files:**
- Modify: `src/main/java/com/example/website/config/AppProperties.java`
- Create: `src/main/java/com/example/website/service/music/ChkszNeteaseMusicClient.java`
- Test: `src/test/java/com/example/website/service/music/ChkszNeteaseMusicClientTests.java`

- [ ] **Step 1: Write failing client contract tests**

Use an OkHttp interceptor with a fake API key. Assert all seven `level` parameters, `type=json`, song ID and key query parameters; map `url`, `level`, `size`, metadata, source and actual source. Add cases for disabled/missing configuration, 400 negative caching, 401/403 cooldown, 402 daily cooldown, 429 `Retry-After`, 503, malformed JSON, business errors and missing URLs. Assert each failure performs at most one HTTP call and never places the fake key in exception messages.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -q -Dtest=ChkszNeteaseMusicClientTests test`.

Expected: compilation fails because the client and ChKSz properties do not exist.

- [ ] **Step 3: Add properties and implement the client**

Extend `AppProperties` with `music.chksz.enabled`, `baseUrl`, and `apiKey`. Build URLs only with `HttpUrl`, reuse `CLIENT_MUSIC_THIRD_PARTY`, constrain each call to five seconds, parse `{code,msg,data}`, validate HTTP(S) URLs, and map successful data to `PlayInfo`.

Use a 30-second `id:level` negative cache, a dedicated `ThirdPartyCircuitBreaker`, and an atomic global cooldown timestamp. Apply ten minutes for 401/403, next UTC+8 midnight for 402, and parsed `Retry-After` or 60 seconds for 429. Never retry within the same request.

- [ ] **Step 4: Run focused tests**

Run: `mvn -q -Dtest=ChkszNeteaseMusicClientTests test`.

Expected: all ChKSz client tests pass.

### Task 3: MusicService Resolver Integration

**Files:**
- Modify: `src/main/java/com/example/website/service/music/MusicService.java`
- Modify: `src/test/java/com/example/website/service/music/MusicServiceTests.java`

- [ ] **Step 1: Write failing resolver-order tests**

Add a mocked `ChkszNeteaseMusicClient`. Assert NetEase `jymaster` calls ChKSz first and skips TuneFree on success; a ChKSz failure maps `hires` to `FLAC24` and enters the existing TuneFree degradation chain; QQ and Kuwo never call ChKSz; and NetEase legacy aliases share canonical cache entries while each response echoes the caller's requested value.

- [ ] **Step 2: Run tests and verify failure**

Run: `mvn -q -Dtest=MusicServiceTests test`.

Expected: new resolver-order assertions fail because `MusicService` does not call ChKSz or parse seven-value qualities.

- [ ] **Step 3: Integrate source-aware requests**

Parse `source` before quality, use the canonical quality in the existing 20-minute cache key, and preserve the caller-facing value in `requestedQuality`. Within the NetEase primary stage call ChKSz once, catch its music business failure, then invoke the existing TuneFree degradation chain with the mapped legacy quality. Pass the legacy quality to cross-source fallback. Leave QQ text fallback and resolver configuration semantics unchanged.

- [ ] **Step 4: Run service and client tests**

Run: `mvn -q -Dtest=PlaybackQualityRequestTests,ChkszNeteaseMusicClientTests,MusicServiceTests test`.

Expected: all focused tests pass.

### Task 4: Runtime Configuration and API Documentation

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application.yml`
- Modify: `deploy/production/backend.env.example`
- Modify: `docs/frontend-api.md`

- [ ] **Step 1: Add safe configuration defaults**

Bind `CHKSZ_ENABLED`, `CHKSZ_BASE_URL`, and `CHKSZ_API_KEY` under `app.music.chksz`. Tests explicitly disable ChKSz. Add only empty/placeholder values to the production environment example; never write the real key.

- [ ] **Step 2: Document the source-specific quality contract**

Update `/api/v1/music/play` documentation so NetEase lists seven values and four legacy aliases while QQ/Kuwo list only the existing four. Document `requestedQuality` and `actualQuality` behavior.

- [ ] **Step 3: Run configuration binding tests through the application context**

Run: `mvn -q -Dtest=MusicLibraryIntegrationTests test`.

Expected: the Spring context starts with ChKSz disabled and music integration tests pass.

### Task 5: Regression Verification

**Files:**
- Verify all modified backend files.

- [ ] **Step 1: Run music tests**

Run: `mvn -q -Dtest='*Music*Tests,*Music*Test,ChkszNeteaseMusicClientTests,PlaybackQualityRequestTests,TuneFreePayClientTests,ThirdPartyCircuitBreakerTests' test`.

Expected: all music unit and integration tests pass.

- [ ] **Step 2: Run the full backend suite**

Run: `mvn -q test`.

Expected: all tests pass. Any unrelated failure from the dirty worktree must be reported separately and must not be hidden or reverted.

- [ ] **Step 3: Build the deployable artifact**

Run: `mvn -q -DskipTests package`.

Expected: `target/website-0.0.1-SNAPSHOT.jar` is created successfully.

- [ ] **Step 4: Review the scoped diff**

Run: `git diff --check` and inspect only the files named in this plan. Confirm no API key appears with `rg -n 'chksz_[A-Za-z0-9_-]+'` in tracked source and documentation.

Expected: no whitespace errors, secret matches, or unrelated edits introduced by this implementation.
