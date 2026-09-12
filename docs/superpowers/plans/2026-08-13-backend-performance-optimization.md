# Backend API Performance Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce repeat database/upstream work and expose latency evidence for high-frequency APIs.

**Architecture:** Use Caffeine cache for identity and navigation DTOs, a keyed single-flight helper for synchronous upstream calls, and bounded in-process timing/counter snapshots exposed to administrators. Keep all existing API contracts and security rules.

**Tech Stack:** Spring Boot 2.7, Spring Cache, Caffeine, Micrometer, OkHttp, JUnit 5, Mockito, MockMvc.

---

### Task 1: Cache public navigation data

**Files:**
- Modify: `src/main/java/com/example/website/config/CacheConfig.java`
- Modify: `src/main/java/com/example/website/service/UserScopeService.java`
- Modify: `src/main/java/com/example/website/service/CategoryService.java`
- Modify: `src/main/java/com/example/website/service/LinkService.java`
- Modify: `src/main/java/com/example/website/service/AdminUserService.java`
- Test: `src/test/java/com/example/website/service/NavigationCacheServiceTests.java`

- [x] Write tests proving repeated admin lookup/navigation reads hit repositories once and category/link writes evict the affected navigation cache.
- [x] Run `mvn -q -Dtest=NavigationCacheServiceTests test` and confirm the cache behavior passes.
- [x] Add Caffeine caches and explicit eviction using DTO lists; cache the first admin id.
- [x] Run the focused test again and existing navigation/auth integration tests.

### Task 2: Add single-flight cache loading

**Files:**
- Create: `src/main/java/com/example/website/service/PerformanceSingleFlight.java`
- Modify: `src/main/java/com/example/website/service/music/MusicService.java`
- Modify: `src/main/java/com/example/website/service/NcatVideoService.java`
- Test: `src/test/java/com/example/website/service/PerformanceSingleFlightTests.java`

- [x] Write a concurrent test showing ten callers for one key execute the loader once and all receive the value.
- [x] Run the focused test and observe the missing implementation failure.
- [x] Implement keyed `CompletableFuture` coalescing with cleanup on completion and exception propagation.
- [x] Wrap cold music/video cache loads while preserving existing TTL and exceptions.
- [x] Run focused music/video tests and the full service test set.

### Task 3: Expose request/upstream metrics

**Files:**
- Create: `src/main/java/com/example/website/config/RequestMetricsInterceptor.java`
- Create: `src/main/java/com/example/website/config/UpstreamMetricsInterceptor.java`
- Modify: `src/main/java/com/example/website/config/WebMvcConfig.java`
- Modify: `src/main/java/com/example/website/config/OkHttpConfig.java`
- Test: `src/test/java/com/example/website/config/RequestMetricsInterceptorTests.java`

- [x] Write tests for normalized route timing and upstream status/error counters.
- [x] Run focused tests and observe the missing metrics implementation failure.
- [x] Register the interceptors and expose only bounded aggregate performance data through the authenticated administrator endpoint.
- [x] Ensure metric tags are bounded and never include credentials or full URLs.
- [x] Run focused tests and `mvn -q test`.

### Task 4: Baseline and verification

**Files:**
- Create: `docs/performance-baseline.md`
- Modify: `README.md`

- [x] Add deterministic commands for local H2/MockMvc and fixture-based upstream timing checks.
- [x] Record call-count and latency observations without claiming production P95 improvements that were not measured.
- [x] Run `mvn -q -DskipTests package` as final verification; full `mvn -q test` has one unrelated pre-existing password-reset fixture failure.
