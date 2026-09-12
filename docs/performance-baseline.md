# Backend Performance Baseline

The repository now exposes bounded performance data at `GET /api/admin/performance` for an authenticated administrator. The response contains request counts and total nanoseconds by method/normalized route/status, upstream counts by status class, upstream errors, and cache hit/miss counts.

Run the deterministic local verification from the project root:

```bash
mvn -q -Dtest=NavigationCacheServiceTests,PerformanceSingleFlightTests,RequestMetricsInterceptorTests test
mvn -q -Dtest=MusicServiceTests,NcatVideoServiceTest,VideoSourceGatewayTest test
```

These tests validate repository/upstream call coalescing and cache behavior without relying on production network conditions. For a deployed instance, collect the endpoint before and after a traffic window and compare average totals per route; production P95 should be measured at the reverse proxy or load balancer because this endpoint intentionally stores aggregate counters rather than raw request samples.

The metrics implementation keeps route dimensions bounded and excludes authorization headers, query strings, request bodies, and complete upstream URLs.
