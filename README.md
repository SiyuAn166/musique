# Musique

Private Android music app (search Bilibili videos, play the audio).
Private use only, not shared, not published.

- Display name: Musique (French for music)
- Package / applicationId: `com.siyu.musique`
- Stack: native Android, Kotlin, Jetpack Compose, single Activity
- minSdk 26, targetSdk 36, compileSdk 36
- Default audio quality (v0.1): 192K, quality id 30280

## Status: Step 3 done (real playback + debug log screen)

Step 1 replaced the Step 0 fake data with the real Bilibili API,
anonymous, no login:

- Device bootstrap in the background: local device identity
  (_uuid, b_lsid, b_nut), finger/spi buvid pair, GenWebTicket
  bili_ticket + WBI keys, one www.bilibili.com activation hit.
  Bootstrap failure never blocks startup or search; state persists
  in DataStore.
- Real search: `search/all/v2`, keyword only, first page only,
  video results only. Titles are cleaned of `<em>` highlight tags
  and HTML entities, covers get an `https:` prefix, durations are
  total minutes ("419:2"). BV input goes straight to details;
  `av<number>` input converts to BV first; blank input sends no
  request.
- Detail backfill on tap: signed `/x/web-interface/wbi/view`
  fills the track's cid (plain view fails anonymously with HTTP
  412, verified in the smoke tests); unsigned pagelist is the
  cid-only fallback.
- Distinct error states: network problem, request blocked
  (-352/-412), too frequent (-799), data format changed, other
  API errors, each with a Retry button. Empty results stay a
  separate state from errors.
- Covers load with Coil; INTERNET permission is in the manifest.

Step 3 adds real sound plus debug logging:

- Play URL resolution: `playurl` with bvid + cid + fnval 4048,
  unsigned; picks the DASH audio stream 30280 (192K) first, then
  30232, then 30216, never Dolby (30250) or Hi-Res (30251).
  URLs are resolved fresh on every tap and never cached.
- Playback: Media3 ExoPlayer, streaming (never download-then-play),
  with the desktop Firefox User-Agent and bilibili.com Referer the
  CDN requires, and a 200MB LRU audio cache keyed by
  bvid + cid + quality. Tap a row to hear it; play/pause and
  previous/next work within the current search results. Player
  states: idle, fetching, buffering, playing, paused, error.
- A failed cid fill is now a visible player error with a Retry
  button (Step 1 kept the tapped track and stayed silent; Step 3
  corrects that because playback needs the cid). On an HTTP
  playback failure the URL is refetched once, then the player
  stops in the error state. Per-tap intent numbers discard stale
  resolves, so tapping A then B never plays A.
- Debug logging: every bootstrap, search, detail, play URL, and
  player event is logged to logcat (tag `Musique`) and to an
  in-app log screen. Open it with the 日志 button on the search
  screen: the last 300 lines, auto-scrolled, with Copy and Clear.
  Logs never contain full play URLs, cookies, tickets, or keys:
  hosts, ids, counts, and error kinds only. On a computer, view
  the same lines with `adb logcat -s Musique`.

Not in Step 3, coming in later steps:

- Step 4: player page, queue, progress, mini player
- Step 5: error polish + error-path tests

Versioning rule: a version is tagged only when its features are
complete, never one version per commit. Step 3 has no version tag;
the version here is a plain development version (`0.1.0-dev`). v0.1
is tagged only when search and play are complete and tested.

## Run on Windows (Android Studio)

Prerequisites: Android Studio (latest stable), JDK 17 (bundled with
Android Studio, no separate install needed), Android SDK 36.

1. Open Android Studio, choose Open, select this `musique` folder.
2. Let Gradle sync finish (first sync downloads dependencies).
   If it asks for the SDK, point it at your Android SDK, or let the
   SDK Manager install API 36.
3. Connect your phone with USB debugging on, or start an emulator
   (API 36 recommended).
4. Press Run (the green triangle). The app opens on an empty search
   screen. Type a keyword (or a BV id, or `av<number>`) and tap
   Search for real Bilibili results, tap a row to play it, tap the
   mini player bar to open the player page, tap 日志 to open the
   debug log screen.

## Run from the command line (Windows PowerShell)

From the `musique` folder:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

`assembleDebug` writes the debug APK under
`app\build\outputs\apk\debug\`. Copy the built file to
`musique.apk` (the delivered file is always called `musique.apk`)
and install it with:

```powershell
adb install -r musique.apk
```

`testDebugUnitTest` runs the unit tests; the report is at
`app\build\reports\tests\testDebugUnitTest\index.html`.

## Build the APK with GitHub Actions

Every push to `main` (and every manual run) builds the debug APK
in CI: see `.github/workflows/build-apk.yml`. It checks out the
repo, sets up Temurin JDK 17 and Gradle, runs
`./gradlew assembleDebug`, renames the built file to `musique.apk`,
and publishes it on the Releases page (one rolling `dev-latest`
release, updated on every push; the same file is also kept as the
`musique` Actions artifact for 30 days). Download `musique.apk`
from https://github.com/SiyuAn166/musique/releases and install it
with `adb install -r musique.apk`.

## Unit tests

Every feature ships with tests in `app/src/test` (168 tests total
after Step 3):

Step 0 (kept, 2 tests adapted to the Step 1 contract: no auto-search
on init, blank sends no request):

- `TrackTest` — key is bvid + cid, two parts of one video differ,
  empty cid allowed before details, duration stored in milliseconds.
- `DurationFormatTest` — "3:48", "419:02" (minutes over 60, as
  Bilibili reports long compilations), "65:09" (no hour rollover:
  60 minutes shows as "60:00", not "1:00:00"), negative clamps
  to "0:00"; plus duration-text parsing ("419:2", "829:59",
  malformed text returns null).
- `FakeSearchRepositoryTest` — the Step 0 fake now lives in the
  test sources as a test double only; blank query returns all 5
  fake tracks, keyword filters by title/artist, unknown keyword
  returns an empty list, keys are unique.
- `SearchViewModelTest` — Idle start, search filtering, Empty
  vs error states, play / pause toggle, next/previous stopping
  at the ends of the queue.

Step 1 (new):

- `AvBvTest` — all 20 live aid/bvid pairs from the Nov 14 probe
  plus av170001, invalid aids rejected.
- `WbiSignerTest` — mixin key and full signed query string match
  independently computed values, key sorting, character filtering,
  %20 encoding, short keys rejected.
- `CryptoTest` — MD5 and HMAC-SHA256 against known vectors
  (RFC 4231 test case 1).
- `CookieJarTest` — domain/path/expiry/secure matching, Max-Age,
  overwrite, malformed headers ignored.
- `TitleTextTest` — `<em>` stripping, entity unescaping, no
  double decode.
- `BiliClientTest` — business codes, v_voucher pseudo-success,
  cookie ingest, headers, transport retries and backoff, risk
  re-sign retry, HTTP 412 paths, signed vs unsigned requests,
  and the no-deadlock regression test for key fetching.
- `WbiKeyStoreTest` — 6-hour cache, expiry, force refresh,
  invalidate, seed.
- `BiliBootstrapTest` — happy path, ticket POST shape, failure
  recording without throwing, stored-state reuse, ticket renewal.
- `ApiServiceTest` — search/detail/nav parsing against fixtures
  trimmed from live probe responses, pagelist fallback.
- `BiliSearchRepositoryTest` — blank sends no request, BV/AV
  direct input, fallback merge.
- `SearchViewModelStep1Test` — error-kind mapping, retry, cid
  fill on tap, detail failure surfaces a player error (Step 3
  contract change: no longer silent, see below).

Step 3 (new):

- `PlayUrlTest` — playurl parsing against a fixture shaped like
  the live probe response, quality pick order 30280/30232/30216
  regardless of server order, Dolby/Hi-Res never picked,
  empty/missing audio errors, unsigned bvid+cid+fnval request
  shape with no qn.
- `MusiqueLogTest` — LogBuffer capacity eviction, ordering, clear,
  snapshot flow, timestamp format; redaction of URLs with query
  strings and secret key=value pairs; host extraction; sink
  fan-out and throwing-sink safety.
- `PlaybackRepositoryTest` — play URL resolution through the real
  repository and a fake gateway, fresh resolve on every call, no
  request without a cid, API error propagation, log lines carrying
  host and quality but never the full URL, bootstrap log lines.
- `SearchViewModelStep3Test` — tap resolves the URL and hands it
  to the engine with a bvid:cid:quality cache key, cid fill before
  resolve, engine callback state transitions, pause/resume through
  the engine, detail failure as a player error with retry, HTTP
  error refetch-once then error, non-HTTP error with no refetch,
  stale resolve and stale callback discard, next/previous through
  the engine, play logging without URLs, clear logs.

Step 1 contract changes in Step 3 (both explicit, both reported):

- Detail failure on tap is now a player error, not silent: the
  Step 1 test `play keeps the tapped track when the detail call
  fails` now expects the Error state and `isPlaying == false`.
- The current track carries the resolved `playUrl` after a
  successful resolve: the Step 1 test `play skips the detail call
  when cid is already filled` now compares the track with the
  `playUrl` cleared. No other Step 0/1 test changed.

### Running the tests without Gradle (sandbox method)

Gradle cannot run in the agent sandbox (see Build status below),
so the pure-Kotlin core and the tests are verified directly with
kotlinc 2.2.10, JDK 17, and the exact jars the project declares
(JUnit 4.13.2, kotlinx-coroutines-test 1.10.2,
lifecycle-viewmodel 2.9.2, OkHttp 4.12.0,
kotlinx-serialization-json 1.8.1). Compile the main sources under
`model/`, `core/network/`, `core/logging/`, `data/` (except the
DataStore file, the wiring object, and the Android log sink),
`player/PlayerEngine.kt` only, and `ui/SearchViewModel.kt`, then
the test sources, then run JUnitCore over the 19 test classes.
The Compose UI files, the ExoPlayer implementation, the DataStore
store, the Android log sink, and the wiring object are statically
reviewed only; the first real Gradle build is on Windows / GitHub
Actions.

## Build status (Nov 14, 2026)

The Gradle build (`assembleDebug`, `testDebugUnitTest`) could not
be run in the agent sandbox: the sandbox blocks the raw TCP
connection between the Gradle client and its daemon on localhost,
so every Gradle invocation fails with "Could not receive a message
from the daemon" (even with the Other TCP switch on, and even with
`--no-daemon`, which still forks a single-use daemon). The project
itself is fine; this is a sandbox network permission, not a project
error. What was verified instead:

- All pure-Kotlin main sources and all 19 test classes compile
  cleanly with kotlinc 2.2.10, and all 168 unit tests pass.
- The full API chain (bootstrap, search, signed wbi/view,
  pagelist, playurl) was verified live with a Python/curl probe
  from the sandbox on Nov 14, 2026, and the parser fixtures come
  from those real responses.
- A live JVM run of the real OkHttp stack could NOT be verified
  from the sandbox: Java networking through the sandbox egress
  proxy times out while establishing the CONNECT tunnel (the
  same proxy quirk that breaks the Gradle wrapper download).
  The bootstrap correctly recorded the failure instead of
  crashing.
- GitHub Actions is the real build machine: CI run 1 failed on
  the gradlew executable bit (fixed with a chmod step), run 2
  went green, and the first APK installed on a real phone
  verified search end to end. The Step 3 additions (Media3
  ExoPlayer, the log screen, the cache) still need their first
  real Gradle run and first real-device playback test: GitHub
  Actions (`.github/workflows/build-apk.yml`) or Windows Android
  Studio. Report any error back verbatim.
