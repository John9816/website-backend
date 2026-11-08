# Music Reliability Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or inline execution; read the spec before implementation.

**Goal:** Make music audio/lyrics identity consistent and keep auxiliary requests bounded, observable and independent of playback latency.
**Architecture:** Add backwards-compatible playback identity fields, isolate lyric loading and cover lookup caching, and reuse existing Caffeine/OkHttp/Micrometer infrastructure.
**Tech Stack:** Java 8 compatible Spring Boot 2.7, Caffeine, Micrometer, Flutter/Dart.
**Spec:** ../specs/2026-11-08-music-reliability-design.md

## Global Constraints

Preserve original source/id and production routes. No database migration. Preserve unrelated local files. Test against controlled fixtures, not production load. No release version bump in implementation.

## Task 1: Recording identity and client consumption

Files: dto/music/PlayInfo.java, service/music/MusicService.java, new RecordingMatch.java, associated tests; DuckMusic music_api.dart, playback URL resolution, player_controller.dart and cache metadata.
- [x] Add failing tests rejecting studio/live and duration mismatches, retaining the selected actual ID.
- [x] Add optional actualId/lyricMatch fields, select compatible recordings and populate provenance for every resolver/cache path.
- [x] Consume structured playback response in App; carry actual identity into lyric requests and cached audio; retain URL-only compatibility callers.
- [x] Verify old response fallback, unverified lyric suppression, actual-source headers and quality behavior.

## Task 2: Lyrics and metrics

Files: new MusicLyricService.java, MusicMetrics.java and tests; integrate in MusicService.
- [x] Test simultaneous requests invoke one upstream, fallback cached during cooldown, official lyrics recover after cooldown and deadlines are shared.
- [x] Implement 8-second total budget, 30-second failure cooldown and current TTL policy with rechecks inside single flight.
- [x] Emit bounded source/operation/outcome timer and cache/lyric counters via Micrometer. Test real registry measurements.

## Task 3: Bounded cover lookup

Files: KuwoMusicClient.java, new cover helper if needed, Kuwo tests.
- [x] Test overlapping pages share one upstream query, failed lookups recover and completed cache has finite retention.
- [x] Replace unbounded cover map, bound queued/in-flight calls and per-call time. Preserve two-second list budget and verified Kuwo image host constraint.
- [x] Test delayed/failed response and ensure caller can return without leaked permits or unresolved futures.

## Task 4: Playback history and overload protection

Files: controller/MusicController.java, new MusicHistoryRecorder.java, OkHttpConfig.java, MusicService.java and tests.
- [x] Test playback response does not await history I/O; bounded retry/duplicate coalescing and eventual persistence.
- [x] Replace CallerRunsPolicy for music with rejection handling and per-source admission; cancel incomplete resolver tasks even if later submission is rejected.
- [x] Verify concurrent saturation keeps requests bounded and releases capacity after error/cancellation.

## Task 5: Verification and review

- [x] Run focused tests after every task, inspect failures before implementation.
- [x] Run mvn clean verify and flutter analyze/test once integrated.
- [x] Review final changes including cache identity, deadlines and compatibility; fix material issues.
- [x] Record results and remaining production-validation limits in a research note.

## Completion record

2026-11-08: all five tasks complete. Backend clean verify: 403 tests; App full test: 534 tests; App analyze and both diff checks passed. Review fixes verified for recording identity cleanup, concurrent quality aliases, graceful history drain and cancellation-neutral provider health. Scoped re-reviews found no remaining actionable issues. See research/2026-11-08-music-reliability.md for behavior, evidence and production-validation limits. No version bump, commit, push or deployment in this task.
