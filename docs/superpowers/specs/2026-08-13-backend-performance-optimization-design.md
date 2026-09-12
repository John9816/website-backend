# Backend API Performance Optimization Design

**Goal:** Reduce repeat database and upstream work on high-frequency API paths while making latency and cache behavior observable and testable.

## Scope

- Cache the administrator identity used by anonymous public navigation requests.
- Cache navigation DTOs by effective user scope and evict them after category/link mutations.
- Add single-flight loading for repeated music and video cache keys to prevent cache stampedes.
- Add lightweight request and upstream timing counters through an in-process metrics component.
- Preserve existing security, response shapes, timeout budgets, and stale video configuration behavior.

## Architecture

The existing Caffeine cache remains the local cache. A small keyed future registry coalesces concurrent cache misses for music/video calls without changing their synchronous APIs. A servlet interceptor records normalized route timings, while an OkHttp interceptor records upstream timings and status classes. A protected administrator endpoint exposes aggregate snapshots.

## Data Flow and Errors

Anonymous navigation resolves the first enabled administrator from the cached identity, then reads cached immutable DTO lists. Any category or link write evicts that administrator's navigation entries. Cache failures are non-fatal and fall through to the repository/upstream. Upstream failures retain existing business exceptions and video last-known-good configuration behavior; metrics must never alter the response.

## Verification

Unit tests prove repository/upstream call counts for cache hits, mutation eviction, and concurrent same-key loading. Spring MVC tests verify route timing registration without exposing credentials. Existing integration and service tests remain the regression suite. Local H2 and deterministic HTTP fixtures are the baseline; production metrics are optional calibration data.
