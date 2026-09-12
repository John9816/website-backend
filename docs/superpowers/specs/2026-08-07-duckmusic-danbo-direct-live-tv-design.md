# DuckMusic Danbo Direct Live TV Design

## Goal

DuckMusic must reproduce Danbo's current television catalog and playback behavior on Android, iOS, macOS, and Windows without depending on the Spring Boot live-TV API or HTTPS media relay.

The live catalog is fetched at runtime from:

`https://develop202.github.io/migu_video/interface.txt`

The initial captured fixture contains 14 groups, 261 unique channels, and 352 ordered playback entries. These values are fixture observations used by acceptance tests, not constants in production code; the runtime catalog follows the evolving remote document.

## Source And Cache

- DuckMusic fetches the M3U document directly with a bounded HTTP timeout.
- The source URL is a compile-time application constant and is not supplied by the backend.
- A successful response is decoded as UTF-8 with malformed byte tolerance, parsed, and then persisted as the last-known-good raw M3U document.
- Entering the television page reads and displays the cache first, then always refreshes from the remote source because line query parameters expire.
- A failed refresh never overwrites a valid cache. The page shows a non-blocking stale-data notice when cached content remains usable.
- If neither cache nor network data is available, the page shows a retryable loading error.
- Pull-to-refresh performs a new remote request and updates both the page and cache only after parsing succeeds.

## M3U Domain Model

The parser accepts an optional UTF-8 BOM, CRLF or LF line endings, quoted `#EXTM3U` and `#EXTINF` attributes, and unrelated comment lines.

Header metadata retained by the model:

- `x-tvg-url` as an ordered EPG URL list.
- `catchup`.
- `catchup-source`.

Entry metadata retained by the model:

- `tvg-id`.
- `tvg-name`.
- `tvg-logo`.
- `group-title`.
- the display title after the `#EXTINF` comma.
- the following playable URI.

The parser groups entries by `group-title` in first-appearance order. Within a group it aggregates entries by `tvg-name`, falling back to `tvg-id` and then the display title. Channel order is first-appearance order. Playback entries are not deduplicated: each original entry becomes one numbered line so the visible line count matches Danbo exactly. The first non-empty logo and TVG ID become channel metadata.

Malformed entries without a usable name or absolute HTTP/HTTPS playback URI are ignored. A document with no usable channel throws a parse error and cannot replace the cache.

## Television Browser

- The page header displays live group, channel, and line counts from the parsed document.
- Horizontal group tabs preserve the source order.
- The channel grid is filtered locally; search matches channel name and TVG ID case-insensitively.
- Each channel tile displays its logo when available, otherwise a television icon, plus its line count.
- Selecting a channel opens the player immediately on its first line. Line selection remains available inside the player.
- Loading, cached-refresh warning, empty search, fatal load failure, and manual refresh states are distinct.

## Playback

DuckMusic replaces `video_player` for live television with `media_kit`, `media_kit_video`, and the platform native video libraries for Android, iOS, macOS, and Windows. The application initializes `MediaKit` before `runApp`.

The live player owns one media player and video controller. It opens the original upstream HTTP or HTTPS URI directly. It does not request an opaque reference, playback token, or relay URL from the backend.

Playback behavior:

- Start with the user-selected line, otherwise line 1.
- Treat an open exception or media error event as a line failure.
- Automatically advance through each remaining line once.
- Never retry a failed line automatically within the same attempt cycle.
- A manual line selection starts a new attempt cycle at that line.
- After all lines fail, show a retry action that starts a fresh cycle.
- Ignore late error events from a superseded open operation.
- Dispose the player and subscriptions when leaving the page.

The player provides play/pause, mute, fullscreen, and line selection controls. Casting is not routed through the removed HTTPS relay; any existing casting path that rejects direct HTTP URLs is not exposed from this live player.

## Platform Requirements

- Android keeps cleartext traffic enabled for direct HTTP line playback.
- iOS keeps the existing ATS allowance for arbitrary media loads.
- macOS retains outgoing network client entitlements.
- Windows uses the `media_kit` native video library bundled by Flutter plugins.
- No platform uses a WebView or browser player.

## Backend Removal

Remove only the live-TV implementation and references:

- `LiveTvController` and `LiveRelayController`.
- `LiveTvException` and its global exception mapping.
- all `dto/live` classes.
- all `service/live` classes.
- dedicated live OkHttp clients.
- `AppProperties.LiveTv` and `LiveSource`.
- the `app.live-tv` configuration block and test configuration.
- live-specific Spring Security matchers and authentication messaging.
- live controller, service, security, configuration tests, and fixtures.
- obsolete live API and relay documentation.

Music, authentication, books, VOD/video, membership, deployment, and other unrelated uncommitted work remain untouched.

## Verification

Automated tests cover:

- BOM, header metadata, quoted attributes, fallback names, malformed entries, ordering, aggregation, and duplicate lines.
- The captured Danbo fixture yielding 14 groups, 261 channels, and 352 lines, including CCTV1 with 7 lines and CCTV5 with 3.
- cache-first loading, successful refresh, stale-cache fallback, and no-cache failure.
- local group/search UI, displayed counts, channel navigation, and retry states.
- line ordering, manual selection, runtime automatic failover, exhaustion, and fresh-cycle retry.
- absence of backend `/api/v1/live/**` code and successful backend test compilation.

Run Flutter analysis and tests, Maven tests, and available local platform builds. Real-stream smoke testing must use the current remote M3U and record which platform builds or devices were actually exercised.
