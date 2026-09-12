# QQ Default High Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make QQ playback return Xunhuisi's official `isure6` stream URL for every allowed client quality value.

**Architecture:** Keep all four client quality values valid. QQ maps `128k` to Xunhuisi `standard` and maps `320k`, `flac`, and `flac24bit` to `high`, while preserving the requested value and reporting the upstream response as the actual quality; TuneFree retains the original requested quality when the QQ primary resolver fails.

**Tech Stack:** Java 8, Spring Boot, JUnit 5, Mockito

---

### Task 1: Reproduce the incorrect QQ default

**Files:**
- Test: `src/test/java/com/example/website/service/music/PlaybackQualityRequestTests.java`

- [x] Add `defaultsQqToHighQuality`, expecting `K320`, cache value `320k`, and requested value `320k` when QQ quality is omitted.
- [x] Run `mvn -Dtest=PlaybackQualityRequestTests#defaultsQqToHighQuality test` and verify it fails because the current value is `flac`.

### Task 2: Implement the source-specific default

**Files:**
- Modify: `src/main/java/com/example/website/service/music/PlaybackQualityRequest.java`
- Test: `src/test/java/com/example/website/service/music/MusicServiceTests.java`

- [x] Default an omitted QQ quality to `MusicQuality.K320` while preserving explicit QQ quality values and the existing defaults for other sources.
- [x] Update the QQ primary resolver service test to omit quality and assert that Xunhuisi receives `K320` before TuneFree.
- [x] Run focused music tests and verify the fallback-chain tests remain green.

### Task 3: Document, deploy, and verify

**Files:**
- Modify: `docs/frontend-api.md`
- Modify: `README.md`

- [x] Document QQ default `320k/high`, explicit lossless behavior, and the unchanged TuneFree fallback chain.
- [x] Run `mvn -Dtest=QqMusicClientTests,MusicServiceTests,PlaybackQualityRequestTests test`.
- [x] Run `mvn -DskipTests package`.
- [x] Deploy to ECS and verify song `003Agdhd2tM867` without `quality` returns a `playUrl` beginning with `https://isure6.stream.qqmusic.qq.com`.
