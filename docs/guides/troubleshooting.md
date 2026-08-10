# Known Issues and Solutions

This runbook collects non-obvious problems and their solutions discovered during development and
QA. Add an entry only when the issue is likely to recur and the fix is not obvious from the
error message alone.


## Index

Read this list first and jump to the one relevant heading — do not read the whole file.

- [Server counter resets to position 1 immediately after the user picks a server](#server-counter-resets-to-position-1-immediately-after-the-user-picks-a-server)
- [WorkManager unit tests: `AlarmManager.setExact()` not available in Robolectric 4.10.2](#workmanager-unit-tests-alarmmanagersetexact-not-available-in-robolectric-4102)
- [Checking for a Gradle dependency: grep build files, never `find /`](#checking-for-a-gradle-dependency-grep-build-files-never-find-)
- [`MainActivitySmokeTest` failures: `NoActivityResumedException` on real device — RESOLVED in SUB-05](#mainactivitysmoketest-failures-noactivityresumedexception-on-real-device--resolved-in-sub-05)
- [Adding a new OkHttp3 test artifact: `mockwebserver` not found in version catalog](#adding-a-new-okhttp3-test-artifact-mockwebserver-not-found-in-version-catalog)
- [Gradle daemon OOM on machines with ≤ 16 GB RAM: heap reduced from 4096m to 2048m](#gradle-daemon-oom-on-machines-with--16-gb-ram-heap-reduced-from-4096m-to-2048m)
- [`RemoteServiceException`: `startForegroundService()` did not call `startForeground()` — crash on first VPN connect after APK update](#remoteserviceexception-startforegroundservice-did-not-call-startforeground--crash-on-first-vpn-connect-after-apk-update)
- [SSE long-poll times out: `readTimeout(0)` required on a child OkHttp client](#sse-long-poll-times-out-readtimeout0-required-on-a-child-okhttp-client)
- [`okhttp-sse` must be pinned to the same version as the main `okhttp` dependency](#okhttp-sse-must-be-pinned-to-the-same-version-as-the-main-okhttp-dependency)
- [SSE reconnect shows stale server data: `onOpen` was a no-op — fixed in SUB-03](#sse-reconnect-shows-stale-server-data-onopen-was-a-no-op--fixed-in-sub-03)
- [SSE hot-reconnect loop when degraded server sends events: `reconnectAttempt.set(0)` in `onEvent` bypassed backoff — fixed in SUB-03](#sse-hot-reconnect-loop-when-degraded-server-sends-events-reconnectattemptset0-in-onevent-bypassed-backoff--fixed-in-sub-03)
- [`ProcessLifecycleOwner` must be registered from the main thread after `startKoin`](#processlifecycleowner-must-be-registered-from-the-main-thread-after-startkoin)
- [Favoriting a server by id will collide across servers without proper IDs](#favoriting-a-server-by-id-will-collide-across-servers-without-proper-ids)
- [Country-code comparisons: case-sensitive in FavoritesStore, case-insensitive in FavoritesFilter and elsewhere — RESOLVED in SUB-02 (superseded prior note)](#country-code-comparisons-case-sensitive-in-favoritesstore-case-insensitive-in-favoritesfilter-and-elsewhere--resolved-in-sub-02-superseded-prior-note)
- [Server-favorite toggle blocks servers with `id <= 0`: defense-in-depth guard at three layers — SUB-03](#server-favorite-toggle-blocks-servers-with-id--0-defense-in-depth-guard-at-three-layers--sub-03)
- [PopupMenu window-leak guard: instance tracking with dismiss-listener null-out — SUB-03](#popupmenu-window-leak-guard-instance-tracking-with-dismiss-listener-null-out--sub-03)
- [`adb input keyevent --longpress` delivers a short press on Android TV](#adb-input-keyevent---longpress-delivers-a-short-press-on-android-tv)
- [Pinned Favorites header scrolled out of view on open — FocusFirstItem must be TV-gated on every sectioned list screen (initial fix: DEF-sub03/DEF-sub05; refinement: DEF-4)](#pinned-favorites-header-scrolled-out-of-view-on-open--focusfirstitem-must-be-tv-gated-on-every-sectioned-list-screen-initial-fix-def-sub03def-sub05-refinement-def-4)
- [`adb shell settings put system system_locales` does not propagate on Samsung/One UI devices](#adb-shell-settings-put-system-system_locales-does-not-propagate-on-samsungone-ui-devices)
- [Restyling stock `PopupMenu`/`AlertDialog` via theme attributes only — no code/behavior diff](#restyling-stock-popupmenualertdialog-via-theme-attributes-only--no-codebehavior-diff)
- [`ServerRepositoryTest.parallel_force_refresh_same_key_does_not_fail_cache_write` is flaky under a full-suite run on Windows](#serverrepositorytestparallel_force_refresh_same_key_does_not_fail_cache_write-is-flaky-under-a-full-suite-run-on-windows)
- [`core` module Robolectric tests can't resolve even plain `@ColorRes` lookups, not just AppCompat/Material theme attributes](#core-module-robolectric-tests-cant-resolve-even-plain-colorres-lookups-not-just-appcompatmaterial-theme-attributes)
- [Engine update build fails with `Failed to find target with hash string 'android-37'` — SDK Platform 37 not yet installed](#engine-update-build-fails-with-failed-to-find-target-with-hash-string-android-37--sdk-platform-37-not-yet-installed)
- [CI's bundled `sdkmanager` cannot resolve `platforms;android-37` even though Gradle can](#cis-bundled-sdkmanager-cannot-resolve-platformsandroid-37-even-though-gradle-can)
- [Removing an enum constant silently deletes regression coverage that a mechanical find/replace doesn't restore](#removing-an-enum-constant-silently-deletes-regression-coverage-that-a-mechanical-findreplace-doesnt-restore)
- [Auto-switch never fires when the live AIDL push status callback stalls — bug 86cb21563](#auto-switch-never-fires-when-the-live-aidl-push-status-callback-stalls--bug-86cb21563)
- [OpenVpnService `RemoteServiceException` (`ForegroundServiceDidNotStartInTimeException`) on reconnect after a background status sync — bug 86cb35fbt](#openvpnservice-remoteserviceexception-foregroundservicedidnotstartintimeexception-on-reconnect-after-a-background-status-sync--bug-86cb35fbt)

---

## Server counter resets to position 1 immediately after the user picks a server

**Status: RESOLVED** — PR #111 (`fix/server-counter-resets-on-connect`)
**Branch fixed on:** `fix/server-counter-resets-on-connect`
**Files changed:** `MainViewModel.kt`, `MainSelectionInteractor.kt`, `MainConnectionInteractor.kt`

### Symptoms

- User opens the server list, taps a specific server (e.g. server 3/10).
- The main screen flashes the correct selection, then immediately reverts to a different server (typically server 1/N or a server that shares an IP with the intended one).
- The counter shown in the details line (`Server: X/N`) does not reflect the user's choice by the time they tap Connect.

### Root causes (three independent bugs, all must be fixed)

**Bug 1 — startup coroutine race in `MainViewModel.loadInitialSelection()`**

`loadInitialSelection()` runs in a coroutine launched during `MainViewModel` init. If the user selects a server while this coroutine is still in-flight, the coroutine's final `updateSelectedServer(...)` call would overwrite the user's explicit selection because it executed after `pendingUserSelectionOverride` was set to `true` but the check was absent.

Fix: after the coroutine receives the result of `selectionInteractor.loadInitialSelection(...)`, check `_state.value.pendingUserSelectionOverride` before calling `updateSelectedServer(...)`. If the flag is `true`, return early — the user's selection wins.

```kotlin
val selection = selectionInteractor.loadInitialSelection(cacheOnly = cacheOnly) ?: return@launch
if (_state.value.pendingUserSelectionOverride) return@launch
updateSelectedServer(...)
```

**Bug 2 — OR-logic IP match in `MainSelectionInteractor.hydrateStoredSelectionFromV2()`**

When hydrating a stored `DEFAULT_V2` selection from the refreshed server list, the old code used an OR condition (`configData == stored OR ip == stored`) that collapsed into a single `indexOfFirst` predicate. Because multiple servers in the same country often share the same IP address (e.g. a pool of servers on one relay), this always resolved to the first server sharing that IP regardless of which specific server the user had selected.

Fix: use a priority search — match `configData` first (unique per server config), fall back to IP only when `configData` is blank or yields no match, and default to index 0 as the final fallback.

```kotlin
val selectedIndex = when {
    !selectedConfig.isNullOrBlank() ->
        legacyServers.indexOfFirst { it.configData == selectedConfig }
            .takeIf { it >= 0 }
            ?: legacyServers.indexOfFirst { !selectedIp.isNullOrBlank() && it.ip == selectedIp }
                .takeIf { it >= 0 }
            ?: 0
    !selectedIp.isNullOrBlank() ->
        legacyServers.indexOfFirst { it.ip == selectedIp }.takeIf { it >= 0 } ?: 0
    else -> 0
}
```

**Bug 3 — stale `configData` in `MainConnectionInteractor.prepareStart()` when `preferUserSelection=true`**

When SSE sync (or any background sync) pushed a new server list from the API, the `selectedServer` held in `MainViewModel` state could carry a stale `configData` from before the sync. `prepareStart()` was reading `configData` directly from `selectedServer.config` (ViewModel state). When `preferUserSelection=true`, this stale value would be passed to the VPN engine instead of the fresh config stored in `SelectedCountryStore`.

Fix: when `preferUserSelection=true`, `prepareStart()` reads `configData` from `SelectedCountryStore.currentServer()` at Connect time rather than from the ViewModel's cached `selectedServer.config`.

### Diagnosis checklist for future regressions

1. Add a logcat filter for `MainViewModel` and `MainSelectionInteractor` tags. Confirm whether `updateSelectedServer` fires *after* a user selection event — if it does, Bug 1 is regressed.
2. In the `DEFAULT_V2` server list for the affected country, check whether multiple servers share the same IP. If yes, and hydration resolves to the wrong one, Bug 2 is regressed.
3. `pendingUserSelectionOverride` is set in `MainViewModel.onServerSelected()`. If that flag is not being set before `loadInitialSelection()` returns, the race window is wider than expected — investigate coroutine scheduling around `MainViewModel` init.
4. If the counter is correct pre-Connect but the wrong server is used by the VPN engine, check whether `prepareStart()` is reading from `SelectedCountryStore.currentServer()` when `preferUserSelection=true` (Bug 3). A stale `configData` from ViewModel state would indicate a regression there.

### Related files

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt` — Bug 1 fix: double-guard in `loadInitialSelection()` and `syncServersForForegroundIfDue()`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainSelectionInteractor.kt` — Bug 2 fix: config-first sequential search in `hydrateStoredSelectionFromV2()`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainConnectionInteractor.kt` — Bug 3 fix: read fresh `configData` from `SelectedCountryStore.currentServer()` in `prepareStart()` when `preferUserSelection=true`

---

## WorkManager unit tests: `AlarmManager.setExact()` not available in Robolectric 4.10.2

**Symptom**

Using `WorkManagerTestInitHelper.initializeTestWorkManager(context)` in unit tests under
Robolectric 4.10.2 causes a crash:

```
java.lang.UnsupportedOperationException: AlarmManager.setExact() not mocked
```

The `WorkManagerTestInitHelper` internally uses `AlarmManager.setExact()`, which is not
available in the Robolectric shadow layer at version 4.10.2.

**Affected tests**

Any `CoroutineWorker` or `OneTimeWorkRequest` unit test that uses
`WorkManagerTestInitHelper.initializeTestWorkManager()`.

**Solution**

Do not use `WorkManagerTestInitHelper` in unit tests. Instead, test the `CoroutineWorker`
`doWork()` method directly by constructing the worker with a fake or in-process
`WorkerParameters` object, and verify return values (`Result.success()`, `Result.retry()`,
`Result.failure()`) without enqueuing through WorkManager.

For deduplication / enqueue policy behaviour, inject a thin `OneTimeWorkEnqueuer` abstraction
and stub it in tests. This is the same pattern used in `ServerRefreshSchedulerTest`.

**First encountered**

SUB-02 (`ProbeRequestWorkerTest`). Fixed by testing `doWork()` directly on a constructed
`ProbeRequestWorker` instance rather than through the WorkManager API.

**References**

- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/probe/ProbeRequestWorkerTest.kt`
- Robolectric issue tracker: https://github.com/robolectric/robolectric/issues (search `AlarmManager setExact`)

---

## Checking for a Gradle dependency: grep build files, never `find /`

**Symptom**

To decide whether Material Components (or any other library) is already available before adding
UI that depends on it, an agent ran a filesystem-wide search for compiled artifacts:

```
find / -iname "MaterialColors.class" ...; find "$HOME" -iname "material-*.aar" ...
```

This scans the entire filesystem (including unrelated mounts/caches) and can run for an hour or
more without finishing, forcing a manual cancel.

**Solution**

Never search the filesystem to answer a "is X already a dependency" question. Grep the Gradle
build files directly instead:

```
rg "com.google.android.material" src/*/build.gradle* src/gradle/libs.versions.toml
```

This answers the question in seconds. If the dependency turns out to be absent, do not add a new
UI library just for a small visual change — prefer a plain `View`/shape-drawable with existing
theme attributes, per CLAUDE.md's "don't introduce new UI/DI patterns" guidance.

**First encountered**

SUB-06 (`FavoritesSectionFrameDecoration`, superseded by SUB-09's `FavoritesSectionCardDecoration`). 
Material Components was already a transitive/declared dependency (used elsewhere in 
`ConnectionControlsView.kt`/`DnsOptionAdapter.kt`), so the search was unnecessary in hindsight — 
a build-file grep would have confirmed this immediately.

---

## `MainActivitySmokeTest` failures: `NoActivityResumedException` on real device — RESOLVED in SUB-05

**Symptom**

All (or most) cases in `MainActivitySmokeTest` fail with `NoActivityResumedException` when run
via `./gradlew connectedDebugAndroidTestApp`. The activity never resumes within the Espresso
timeout window.

**Status: RESOLVED** — All 7 `MainActivitySmokeTest` cases now pass (21/21 total, 0 failures)
on Samsung Galaxy A71 SM-A715F, Android 13 (ADB serial R58N849XQEY). Fixed in SUB-05.

**Actual root cause**

The `ActivityScenario.launch()` intents included `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`
flags. These flags conflict with `ActivityScenario`'s internal lifecycle management, preventing the
activity from reaching RESUMED state inside the test runner.

Additionally, the async `PromptUpdate` effect triggers an update dialog on first launch, which must
be dismissed before subsequent UI interactions can proceed.

> **Note on the previously stated root cause:** An earlier version of this entry attributed the
> failure to an `OkHttpIdlingResource` / `OkHttpIdlingResource` that never became idle. That
> explanation was incorrect. There is no `IdlingRegistry` usage anywhere in the codebase — grep
> confirms no `IdlingRegistry` is registered. The actual cause was the intent flags described above.

**Fix applied (SUB-05)**

1. Removed `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` from all `ActivityScenario.launch()`
   intents in `MainActivitySmokeTest` and `MainActivityUiTest`.
2. Added `dismissUpdatePromptIfVisible()` to the test helper to handle the async update dialog shown
   on launch.

**First confirmed**

SUB-02 docs cycle (2026-06-15). Fixed in SUB-05 (2026-06-18).

### MIUI Android 11 limitation

On Xiaomi Mi 9T Pro (ADB serial b6e8f6bd, Android 11 / MIUI), the Android 11 background activity
start restriction prevents `ActivityScenario.launch()` from launching activities when called from
the instrumentation runner. The tests block indefinitely — there is no timeout and the process must
be killed manually.

- Applying the device idle whitelist (`adb shell cmd deviceidle whitelist +com.yahorzabotsin.openvpnclientgate`)
  does **not** help on MIUI.
- **Workaround:** test on Samsung or stock Android devices. The Samsung Galaxy A71 (Android 13)
  passes all 21 cases after the SUB-05 fix.

**References**

- `src/mobile/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/mobile/MainActivitySmokeTest.kt`
- `src/mobile/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/mobile/MainActivityUiTest.kt`
- the ClickUp story

---

## Adding a new OkHttp3 test artifact: `mockwebserver` not found in version catalog

**Symptom**

Attempting to add `testImplementation("com.squareup.okhttp3:mockwebserver:...")` as a bare
coordinate fails to resolve the correct version, or the artifact is absent from
`libs.versions.toml`, causing a version-catalog lint error:

```
Could not find libs.okhttp.mockwebserver
```

**Root cause**

The version catalog (`src/gradle/libs.versions.toml`) contained `square-okhttp` as a version
reference for the runtime `okhttp` library but had no catalog alias for the companion
`mockwebserver` artifact.

**Solution**

Add an entry to `[libraries]` in `src/gradle/libs.versions.toml` using the existing
`square-okhttp` version reference so both artifacts stay in lock-step:

```toml
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "square-okhttp" }
```

Then declare the dependency in the module's `build.gradle.kts` as test-only:

```kotlin
testImplementation(libs.okhttp.mockwebserver)
```

This keeps the MockWebServer version identical to the OkHttp runtime version without duplicating
the version number.

**First encountered**

SUB-03 (`HardProbeApiClientTest`).

**References**

- `src/gradle/libs.versions.toml` (line 89)
- `src/core/build.gradle.kts` (line 144)
- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/probe/HardProbeApiClientTest.kt`

---

## Gradle daemon OOM on machines with ≤ 16 GB RAM: heap reduced from 4096m to 2048m

**Symptom**

Gradle daemon crashes with an `OutOfMemoryError: Java heap space` (or the build process is
killed by the OS) on a development machine with 11 GB RAM when `org.gradle.jvmargs` is set to
`-Xmx4096m`.

**Root cause**

The default heap ceiling in `src/gradle.properties` was `-Xmx4096m`. On machines where the OS
and other processes already consume a significant portion of available RAM, the Gradle daemon
cannot allocate a contiguous 4 GB heap, causing OOM failures or excessive GC pauses.

**Solution**

Reduce the heap in `src/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
```

`2048m` is sufficient for a full debug build and all unit tests on the project as of SUB-03.

**CI note**

If CI runners provide more than 8 GB RAM and need faster incremental builds, override via the
`GRADLE_OPTS` environment variable in the CI workflow rather than changing
`gradle.properties`:

```yaml
env:
  GRADLE_OPTS: "-Xmx4096m -Dfile.encoding=UTF-8"
```

This keeps the committed default safe for low-memory machines while giving CI full headroom.

**First encountered**

SUB-03 (2026-06-16) on Samsung developer workstation with 11 GB RAM.

**References**

- `src/gradle.properties` (line 8)

---

## `RemoteServiceException`: `startForegroundService()` did not call `startForeground()` — crash on first VPN connect after APK update

**Symptom**

App crashes on the first VPN connection attempt after an APK update with:

```
android.app.RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()
```

The affected service is `com.yahorzabotsin.openvpnclientgate/.vpn.OpenVpnService`. The crash is a
one-shot event: the second attempt (after auto-restart) succeeds. Subsequent launches are also
unaffected because ART has already compiled the DEX cache.

**Root cause**

Race condition between two paths in the service lifecycle:

1. `MainActivityCore.onStart()` → `VpnManager.syncStatus()` → `context.startService()` creates
   `OpenVpnService`. `onOneShotInitialStateSynced()` posts `stopAfterOneShotSyncRunnable` with a
   1 000 ms delay.
2. The user taps Connect within ≤ 1 s of returning to the main screen →
   `VpnManager.startVpn()` → `ContextCompat.startForegroundService()`. Android's ActivityManagerService
   starts the **5-second foreground timer** at this point.
3. `stopAfterOneShotSyncRunnable` fires → `stopSelf()` is called while `ACTION_START` delivery is
   still in flight. The service briefly enters a dead/restarting state.
4. The 5-second timer expires before `startForeground()` is registered to that timer slot →
   `RemoteServiceException`.

**Why only after an update:** ART recompiles class files on the first launch after an APK update.
`onCreate()` and surrounding class initialization run measurably slower, widening the race window
between `stopSelf()` and `startForeground()`. Second and subsequent launches use already-compiled
DEX and are fast enough to avoid the race.

**Fix applied**

`OpenVpnService.onCreate()` now calls `enterControllerForeground(stopOnFailure = false)` immediately
after notification channel setup (`ensureEngineNotificationChannels()`). This satisfies Android's
5-second foreground requirement within `onCreate()` itself, before any intent is delivered —
eliminating the race window entirely.

The `stopOnFailure` parameter was added to `enterControllerForeground()` so that a failure during
`onCreate()` (where the triggering intent is not yet known) does not stop the service prematurely.
The subsequent `ACTION_START` delivery in `onStartCommand()` retries with the default
`stopOnFailure = true`.

**Evidence**

- Crash log: `D:\Apps\OpenVPNClient\OpenVPNClientClientReports\crash after update\logcat_20260617_135358\logcat_20260617_135358.txt`
- Manual QA: Samsung Galaxy A71 SM-A715F Android 13 (ADB serial R58N849XQEY), 2026-06-18 — first
  connect after update succeeded without crash.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
- the ClickUp story

**Related but distinct:** this same exception/service/mechanism recurred later via a different
trigger path — see
[OpenVpnService `RemoteServiceException` (`ForegroundServiceDidNotStartInTimeException`) on
reconnect after a background status sync — bug
86cb35fbt](#openvpnservice-remoteserviceexception-foregroundservicedidnotstartintimeexception-on-reconnect-after-a-background-status-sync--bug-86cb35fbt)
below. This fix (8b2a778) covers the `onCreate()` race; that one covers a stale
`controllerForegroundActive` flag skipping a required *subsequent* `startForeground()` call.

---

## SSE long-poll times out: `readTimeout(0)` required on a child OkHttp client

**Symptom**

The `SseServerEventsClient` connection opens but drops after the default OkHttp read timeout
(10 s by default). Logcat shows `SSE connection failure` with `SocketTimeoutException` or
`SSE connection closed` appearing shortly after `SSE connection opened`, followed by repeated
exponential-backoff reconnect cycles.

**Root cause**

SSE uses a persistent HTTP connection that holds open indefinitely between server events (long-polling).
OkHttp's default read timeout fires if no bytes arrive within the timeout window, terminating the
connection. A well-behaved SSE endpoint may send no data for minutes between actual events.

**Solution**

Create a **child** `OkHttpClient` with `readTimeout(0, TimeUnit.SECONDS)` (zero = no timeout)
for the SSE connection only. Do **not** mutate the shared singleton `OkHttpClient` injected by
Koin, because that client is reused by all other API calls and should retain its default timeouts:

```kotlin
val sseOkHttpClient = okHttpClient.newBuilder()
    .readTimeout(0, TimeUnit.SECONDS)
    .build()
val factory = EventSources.createFactory(sseOkHttpClient)
```

`okHttpClient.newBuilder()` creates a shallow copy that shares connection pools and interceptors
with the parent but can override individual settings. The parent client's read timeout is
preserved.

**First encountered**

SUB-02 (`SseServerEventsClient`) — MP-20260621 SSE client story.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt` (`connectOnce`)

---

## `okhttp-sse` must be pinned to the same version as the main `okhttp` dependency

**Symptom**

Build or runtime classpath conflict between `okhttp` and `okhttp-sse` (e.g.,
`NoSuchMethodError`, `ClassNotFoundException`, or Gradle version-conflict warnings) when
the two artifacts are pinned to different versions.

**Root cause**

`com.squareup.okhttp3:okhttp-sse` is a companion artifact in the OkHttp3 release train. It
shares internal classes with `com.squareup.okhttp3:okhttp`. If they are pinned to different
versions, the JVM can load classes from one version that reference methods only present in the
other, producing silent failures or hard crashes.

**Solution**

In `src/gradle/libs.versions.toml`, add the `okhttp-sse` catalog entry using the existing
`square-okhttp` version reference so both artifacts are always in lock-step:

```toml
okhttp-sse = { group = "com.squareup.okhttp3", name = "okhttp-sse", version.ref = "square-okhttp" }
```

Then declare the dependency in `src/core/build.gradle.kts`:

```kotlin
implementation(libs.okhttp.sse)
```

Never use a bare `"com.squareup.okhttp3:okhttp-sse:x.y.z"` coordinate in `build.gradle.kts`
— it will drift out of sync when the main OkHttp version is bumped.

**First encountered**

SUB-02 (`SseServerEventsClient`) — MP-20260621 SSE client story.

**References**

- `src/gradle/libs.versions.toml` (`okhttp-sse` entry)
- `src/core/build.gradle.kts` (`okhttp-sse` dependency)

---

## SSE reconnect shows stale server data: `onOpen` was a no-op — fixed in SUB-03

**Symptom**

After an SSE reconnect (foreground return or network restore), the displayed server list remained
stale until either the next `servers-changed` push event or the next WorkManager periodic refresh.
Users saw outdated server availability, ping indicators, or country lists after coming back online.

**Root cause**

`SseServerEventsClient.onOpen` did nothing beyond recording the connection-open timestamp. Server
sync fired only when the backend pushed an explicit `servers-changed` event. If the backend had
pushed changes while the client was offline (backgrounded or network-down), those changes would
not be applied until the *next* push event — which might be hours away.

**Fix applied (SUB-03 — MP-20260623-sse-reliability-fixes)**

Added `syncCoordinator.sync(forceRefresh = true, cacheOnly = false)` inside `onOpen`. The call is
dispatched to a new coroutine on `clientScope` (same pattern as `handleServersChangedEvent`) so
the OkHttp callback thread is not blocked. This ensures the server list is always fresh on every
reconnect, independent of whether a push event follows.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt` (`onOpen`)
- the ClickUp story

---

## SSE hot-reconnect loop when degraded server sends events: `reconnectAttempt.set(0)` in `onEvent` bypassed backoff — fixed in SUB-03

**Symptom**

When a degraded backend server was sending SSE events but immediately dropping the connection
afterwards (e.g., due to a misbehaving server-side keep-alive), the client entered a tight
reconnect loop. Rather than backing off exponentially, it reconnected almost immediately on every
cycle.

Logcat evidence: repeated "SSE connection opened / SSE event received / SSE connection closed /
SSE connecting (attempt=0)" sequences with no delay between cycles.

**Root cause**

`onEvent` contained `reconnectAttempt.set(0)`. Receiving even a single event was enough to
reset the backoff counter to zero, so when the degraded server dropped the connection a fraction
of a second later, the next reconnect loop iteration started with `attempt=0` and no delay. This
defeated the exponential backoff entirely and caused CPU/battery waste and excessive reconnect
traffic to the backend.

**Fix applied (SUB-03 — MP-20260623-sse-reliability-fixes)**

Removed `reconnectAttempt.set(0)` from `onEvent`. Backoff reset now happens **only** in
`onClosed` and `onFailure` via `maybeResetBackoff()`, which enforces a stability-threshold guard:
the counter is reset only when the connection was alive for at least
`STABLE_CONNECTION_RESET_DELAY_MS` (10 000 ms). A connection that drops within 10 s of opening
retains its backoff counter regardless of how many events it delivered.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt` (`onEvent`, `maybeResetBackoff`)
- the ClickUp story

---

## `ProcessLifecycleOwner` must be registered from the main thread after `startKoin`

**Symptom**

Calling `ProcessLifecycleOwner.get().lifecycle.addObserver(...)` before `startKoin` completes
(or from a background thread during `Application.onCreate()`) results in one of:
- `IllegalStateException` from Koin: `No definition found for ...` (Koin not yet started)
- `CalledFromWrongThreadException`: `Only the original thread that created a view hierarchy
  can touch its views` (lifecycle observer API called from a background thread)
- Silently missing the first `onStart` event because the observer was added after the process
  already entered the foreground

**Root cause**

`ProcessLifecycleOwner` is a main-thread-only component. `Lifecycle.addObserver()` must be
called on the main thread. Additionally, the `SseServerEventsClient` is resolved from Koin, so
Koin must be initialized first.

**Solution**

Register the `ProcessLifecycleOwner` observer in `Application.onCreate()`, on the main thread,
**after** `startKoin` returns:

```kotlin
override fun onCreate() {
    super.onCreate()
    // 1. Initialize Koin first
    startKoin { ... }
    // 2. Then register lifecycle observers on the main thread
    registerSseLifecycleObserver()
}

private fun registerSseLifecycleObserver() {
    val sseClient = GlobalContext.get().get<SseServerEventsClient>()
    ProcessLifecycleOwner.get().lifecycle.addObserver(sseClient)
}
```

`Application.onCreate()` always runs on the main thread, so no explicit `Handler(mainLooper).post`
is needed as long as `registerSseLifecycleObserver()` is called directly (not dispatched to a
background coroutine). Wrap in `runCatching` to prevent a Koin resolution failure from crashing
the whole process.

**First encountered**

SUB-02 (`CoreApp.registerSseLifecycleObserver()`) — MP-20260621 SSE client story.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/CoreApp.kt` (`registerSseLifecycleObserver`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt`

---

## Favoriting a server by id will collide across servers without proper IDs

**Context:** implementing server-favorite UI on top of `FavoritesStore`/`FavoritesFilter` (SUB-02/SUB-03 of MP-20260706-favorite-countries-servers).

**Problem:** `Server.id` (`src/core/.../servers/Server.kt`) defaults to `0` and is only populated with a real value by `ServerV2.toLegacyServer()`. When a server source does not use the V2 API (originally: `LEGACY`, `VPNGATE`, or `CUSTOM`; now only `VPNGATE` remains after US-15 removed `LEGACY` and `CUSTOM`), every `Server` in the list keeps `id == 0`. Favoriting one such server marks all of them as favorited under `FavoritesStore`, since favorites are keyed purely by `Server.id`.

**Solution:** `FavoritesStore.addFavoriteServer()` guards against `serverId <= 0` (added after PR #114 round-4 bot feedback), so `id == 0` can never be persisted as a favorite — this closes the immediate collision. For current code: server-favoriting is restricted to `DEFAULT_V2` sources only, so only servers with valid V2 IDs (positive integers) can be favorited. Non-V2 sources like `VPNGATE` cannot be favorited.

**Commands/code:** n/a — design note, not a runtime fix.

---

## Country-code comparisons: case-sensitive in FavoritesStore, case-insensitive in FavoritesFilter and elsewhere — RESOLVED in SUB-02 (superseded prior note)

**Context:** `FavoritesStore` (SUB-01) originally persisted favorite country codes with plain string equality (raw values), while `FavoritesFilter.filterFavoriteCountries()` and `CountryServersInteractor.getServersForCountryV2()` (`src/core/.../servers/CountryServersInteractor.kt:64`) both match country codes with `equals(ignoreCase = true)`. SUB-01's review/gate carried this forward as a non-blocking risk, on the assumption that case-insensitive filtering elsewhere was enough.

**Problem (materialized in SUB-02):** `ServerListViewModel.buildItems()` built the pinned favorites section using the same case-insensitive matching as `FavoritesFilter`, but `toggleFavorite()` decided add-vs-remove via `FavoritesStore.isFavoriteCountry()` — still an exact, case-sensitive lookup. If a country code's casing ever drifted between the persisted favorite and a freshly synced code, the toggle handler disagreed with the display filter: duplicate/differently-cased favorite entries, wrong "Add"/"Remove" popup label, wrong toast. Confirmed as a real, reachable defect by SUB-02 code review round 1 (ClickUp QA evidence), not just a theoretical risk.

**Solution:** Normalized casing at the `FavoritesStore` boundary itself (single source of truth) — `addFavoriteCountry`, `removeFavoriteCountry`, `isFavoriteCountry`, and `getFavoriteCountryCodes` now all uppercase country codes via `Locale.ROOT` before storing/comparing/returning. This supersedes the SUB-01 note's assumption that keeping the store case-sensitive was safe — it wasn't, once a second call site (the toggle handler) needed to agree with a case-insensitive display filter. Any future store with a similar "raw persistence + case-insensitive display filter elsewhere" split should normalize at the store boundary from the start rather than relying on every caller to filter consistently.

**First encountered**

SUB-01 (`FavoritesStore.kt`, `FavoritesFilter.kt`) — MP-20260706-favorite-countries-servers, flagged as non-blocking during code review and quality gate. Materialized as a blocking defect and fixed in SUB-02 (`ServerListViewModel.kt`, `FavoritesStore.kt`), commit `ae6f393`.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesStore.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesFilter.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/CountryServersInteractor.kt:64`
- ClickUp QA evidence, ClickUp QA evidence
- ClickUp QA evidence, ClickUp QA evidence

---

## Server-favorite toggle blocks servers with `id <= 0`: defense-in-depth guard at three layers — SUB-03

**Symptom**

Attempting to favorite a server with `id <= 0` (non-V2 sources: originally `LEGACY`, `VPNGATE`, `CUSTOM`; now only `VPNGATE` after US-15) would collide with all other non-V2 servers under the same zero ID. If a user long-pressed such a server and tapped "Add to favorites," **all** non-V2 servers would be marked favorited under `FavoritesStore`, not just the one tapped.

**Root cause**

`Server.id` defaults to `0` and is only populated with a real integer by `ServerV2.toLegacyServer()` hydration. When `UserSettingsStore.load(ctx).serverSource` is not `DEFAULT_V2`, every server in the list retains `id == 0`. Favorites are keyed purely by `Server.id`, so favoriting under `id == 0` is a collision vector. The immediate pre-condition is: avoid persisting `id <= 0` as a favorite key.

**Solution — defense-in-depth**

Three independent guards ensure `id <= 0` never reaches persistent storage:

1. **ViewModel layer** (`CountryServersViewModel.toggleFavorite()`): Early return if `serverId <= 0` before calling `favoritesStore`.
2. **Activity layer** (`CountryServersActivity.onLongClickServer()`): Hide the `PopupMenu` action entirely when `item.id <= 0`.
3. **Store layer** (`FavoritesServerStore.addFavoriteServer()`): Guard with `require(serverId > 0)` before persisting, so even an unexpected call site cannot bypass the check.

This multi-layer defense means each layer can be audited independently. A breach in one layer (e.g., ViewModel check removed) is still caught by the next (Activity hides the menu, or Store rejects it). Any new code path that favorits servers must also satisfy the ViewModel and Activity guards before the Store write is reachable.

**First encountered**

SUB-03 (`CountryServersViewModel.kt`, `CountryServersActivity.kt`, `FavoritesServerStore.kt`) — MP-20260706-favorite-countries-servers.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/CountryServersViewModel.kt` (`toggleFavorite`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/CountryServersActivity.kt` (`onLongClickServer`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesServerStore.kt` (`addFavoriteServer`)

---

## PopupMenu window-leak guard: instance tracking with dismiss-listener null-out — SUB-03

**Symptom**

Showing a new `PopupMenu` while an old one is still anchored (e.g., user taps long-press, cancels the menu by tapping elsewhere, then immediately long-presses again on a different item) causes the old menu's window to persist invisibly, consuming system resources and potentially blocking recomposition or triggering layout warnings in instrumented tests.

**Root cause**

`PopupMenu` holds a reference to a `PopupWindow` that remains attached to the window manager after the menu is hidden/dismissed. Showing a second `PopupMenu` without explicitly dismissing the first leaves both windows active — the second is visible on top, but the first remains allocated in the window manager.

**Solution**

Track the active `PopupMenu` instance in a mutable reference and dismiss it before showing a new one:

```kotlin
private var activePopupMenu: PopupMenu? = null

private fun showPopupMenu(view: View, item: Item) {
    // Dismiss any pre-existing popup
    activePopupMenu?.dismiss()
    
    activePopupMenu = PopupMenu(this, view).apply {
        // Populate menu items...
        setOnMenuItemClickListener { ... }
        setOnDismissListener {
            // Clear reference only if this is still the active popup
            if (activePopupMenu === this) {
                activePopupMenu = null
            }
        }
        show()
    }
}

override fun onDestroy() {
    super.onDestroy()
    activePopupMenu?.dismiss()
    activePopupMenu = null
}
```

Key points:
- Dismiss before showing: prevents stale window from accumulating.
- Check reference identity in `setOnDismissListener` before null-out: avoids clobbering a newer popup that fired `setOnDismissListener` after we already created a replacement.
- Dismiss in `onDestroy`: cleans up on activity destruction.

**First demonstrated**

SUB-03 (`CountryServersActivity.kt`).

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/CountryServersActivity.kt` (`showPopupMenu`, `onDestroy`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/ServerListActivity.kt` (identical pattern, SUB-02)

---

## `adb input keyevent --longpress` delivers a short press on Android TV
**Context:** Manual QA of D-pad long-press features (SUB-04 FavoriteActionDialog) on MIBOX4 / Android 9 TV over adb.
**Problem:** Both `input keyevent --longpress KEYCODE_DPAD_CENTER` and `--longpress 23` fire the row's click (short press) instead of `performLongClick` — the injected pair never holds the key across the confirm-key long-press timeout, so the dialog cannot be opened via `input`.
**Solution:** Prefer a stationary touch hold — `input swipe <x> <y> <x> <y> 800` — which needs no device-node lookup. `sendevent` also works but is node- and scancode-specific: resolve both with `getevent -pl` first (on MIBOX4 the "Xiaomi RC" is `/dev/input/event2`, KEY_SELECT scancode 353 maps to DPAD_CENTER). Canonical write-up, including why one recorded run found `sendevent` ineffective: [../operations/device-qa-tv.md](../operations/device-qa-tv.md).
**Commands/code:**
```
adb -s <tv> shell "sendevent /dev/input/event2 1 353 1 && sendevent /dev/input/event2 0 0 0 && sleep 1.2 && sendevent /dev/input/event2 1 353 0 && sendevent /dev/input/event2 0 0 0"
```
See docs/operations/device-qa-tv.md for the full TV QA runbook (Leanback launch, dialog focus gotchas).

---

## Pinned Favorites header scrolled out of view on open — FocusFirstItem must be TV-gated on every sectioned list screen (initial fix: DEF-sub03/DEF-sub05; refinement: DEF-4)

**Context:** Screens with a pinned Favorites section at adapter position 0 (SUB-02/SUB-03 pattern) also emit a `FocusFirstItem` effect that scrolls/focuses the first *data* row (position 1) for TV D-pad UX.

**Initial problem (DEF-sub03/DEF-sub05):** On touch devices the unconditional `scrollToPosition(1)` scrolls the pinned header out of the attached RecyclerView range on cold open when a favorite already exists (header only reappears on manual scroll-up). Recurred twice: DEF-sub03 on `CountryServersActivity`, then the identical unfixed sibling DEF-sub05 on `ServerListActivity` — fixing one screen does not fix the class.

**Initial solution (DEF-sub03/DEF-sub05):** Gate the `FocusFirstItem` handling on `TvUtils.isTvDevice` via a testable `applyFocusFirstItem` seam: touch devices skip scroll+focus entirely; TV keeps scroll-then-focus. Added matching Robolectric focus tests (`ServerListActivityFocusTest`, `CountryServersActivityFocusTest`).

**Refinement (DEF-4 defect-fix):** The initial gate-to-TV solution proved incomplete. On TV, even gated to `isTvDevice==true`, the function was still calling `scrollToPosition(position)` where `position` was the *focus target* (1 when a header exists, 0 when none) — this scrolls item 1 to the top, pushing the header (item 0) out of the RecyclerView bounds. The issue recurred on real TV hardware after the SUB-09 visual redesign (filled card + second section header) made the cut-off header card edges more visually jarring. **Key insight:** scroll target and focus target are two different concerns:
- **Scroll target** should always be 0 (keep the pinned header/card visible at the top).
- **Focus target** should be position (1 with header, 0 without, so D-pad focus naturally lands on the first *data* row).

**DEF-4 fix:** Decouple scroll and focus in `TvUtils.applyFocusFirstItem()` — always call `scrollToPosition(0)` first (keeping the header visible), then call `focusWhenReady(position)` independently. Position 0 and 1 are adjacent, so `RecyclerView` has already bound/laid out position 1 by the time focus is requested after scrolling to 0 — no additional bind-wait needed. Tests verify both scroll target (always 0) and focus target (position) independently.

**When to apply the gate:** TV-only concern; touch devices return early from `applyFocusFirstItem` before any scroll is emitted. When adding a pinned section (or the `FocusFirstItem` effect) to any new list screen, ensure the activity calls `applyFocusFirstItem` with the correct `isTvDevice` detection, and add the matching Robolectric focus test to verify both header-present (position=1) and header-absent (position=0) cases.

**First encountered:** SUB-03 (`CountryServersActivity`, commit 8c5928e); recurrence caught by SUB-05 manual E2E (`ServerListActivity`, commit d391eb8); refined in SUB-08+SUB-09 defect-fix (`TvUtils.applyFocusFirstItem` decoupling, commits 46351c3/22ca39a).

---

## `adb shell settings put system system_locales` does not propagate on Samsung/One UI devices
**Context:** Manual QA of locale-dependent UI text (SUB-07 favorites string translations) on a Samsung Galaxy A71 (One UI) over adb.
**Problem:** Setting the system-wide locale via `adb shell settings put system system_locales <locales>` does not reliably take effect for a running or freshly relaunched app on Samsung/One UI — `mGlobalConfiguration` stays pinned to the prior locale even after force-stop and relaunch, so RU/PL string verification silently keeps showing the wrong locale's strings.
**Solution:** Use the Android 13+ per-app locale override instead, which One UI honors:
```
adb shell cmd locale set-app-locales <package> --user 0 --locales <locale>   # e.g. pl-PL
# ... test ...
adb shell cmd locale set-app-locales <package> --user 0 --locales ""         # clear override when done
```
Force-stop and relaunch the app after setting the override. See `docs/operations/device-qa-log.md` for the full walkthrough.
**First encountered:** SUB-07 (`favorites-localization` manual QA, Samsung Galaxy A71 `<your-device-serial>`).

---

## Restyling stock `PopupMenu`/`AlertDialog` via theme attributes only — no code/behavior diff
**Context:** SUB-08 needed the mobile long-press favorite `PopupMenu` and the TV `FavoriteActionDialog`'s `AlertDialog` to match the app's visual design instead of stock widget chrome.
**Summary:** Both widgets resolve appearance from theme attributes read at construction time (`android:popupMenuStyle` / `alertDialogTheme`), not from wrapping/subclassing — so both can be restyled via `values/themes.xml` with zero Kotlin call-site changes. The full narrative (why the first attempt still looked stock, the elevation-overlay confusion, the eventual `android:windowBackground`/stroke fix) is documented once, in detail, in `docs/features/favorites.md`'s "Themed Styling (SUB-08)" and "TV D-pad Dialog Pattern" sections — read those for the canonical telling instead of this summary.
**Rule going forward:** any styling/theming acceptance criterion must be verified with an actual on-device screenshot, visually compared against the stock/default widget — a style being technically wired up does not guarantee it is visually distinguishable.
**First encountered:** SUB-08 (`favorites-section-and-dialog-redesign`).

---

## `ServerRepositoryTest.parallel_force_refresh_same_key_does_not_fail_cache_write` is flaky under a full-suite run on Windows
**Context:** Independent test-suite verification during the `favorites-section-and-dialog-redesign` code review (commit `3550da6`), unrelated to the favorites/dialog changes under review — the test file is untouched by that commit.
**Problem:** `./gradlew testDebugUnitTestApp --rerun-tasks` occasionally fails this one test with `java.io.FileNotFoundException` reading a Robolectric temp-dir cache CSV (`ServerRepository.parseServers`), while the rest of the 760-test suite passes. The test exercises concurrent force-refreshes racing to write/read the same on-disk cache file; under full-suite parallel test execution on Windows the file can be deleted/replaced by a sibling coroutine between an existence check and the read.
**Solution:** Re-running just the failing test in isolation (`--tests "...ServerRepositoryTest.parallel_force_refresh_same_key_does_not_fail_cache_write" --rerun-tasks`) passes, and a second full `--rerun-tasks` run of the whole suite also passed clean (0 failures). Treat a lone failure of this specific test as environment flakiness, not a regression, unless `ServerRepository.kt` or its test was actually touched by the diff under review — always cross-check `git show --stat <commit> -- '*ServerRepository*'` before accepting that explanation.
**First encountered:** Code review of `favorites-section-and-dialog-redesign` (commit `3550da6`).

---

## `core` module Robolectric tests can't resolve even plain `@ColorRes` lookups, not just AppCompat/Material theme attributes
**Context:** Quality gate for `favorites-section-and-dialog-redesign` (DEF-2 defect-fix round) attempted to close a coverage gap for `FavoriteActionDialog`'s title-color fix by extracting a testable seam (`resolveThemedTitleColor(context): Int`) that calls `ContextCompat.getColor(context, R.color.text_color_primary)` directly — deliberately avoiding any AppCompat/Material theme-attribute resolution, since `FavoriteActionDialogTest`'s existing class KDoc already documents that *those* can't resolve in this module's Robolectric setup (legacy resources mode).
**Problem:** Even this plain, non-themed color-resource lookup throws `android.content.res.Resources$NotFoundException` when run from `:core:testDebugUnitTest` (`ShadowLegacyAssetManager.getResName` fails to resolve the `core` module's own `R.color` id). The previously documented constraint ("AppCompat/Material theme resources don't resolve") undersold the actual limitation — it is not specific to theme attributes; direct `@ColorRes`/`@DrawableRes`/etc. lookups against this module's own resources fail too under `RuntimeEnvironment.getApplication()`'s legacy resource shadow, at least without additional Robolectric config (e.g. a manifest/package override) not currently present in this module's test setup.
**Solution:** No fix applied — this is a genuine test-environment gap, not a code defect. When a defect fix in `core` needs a resolved-resource-value assertion (color, dimension, drawable) and a full themed Activity/dialog can't be launched either, do not assume a "resolve just the raw resource, skip the theme" workaround will succeed — verify with a throwaway test run first. If it fails the same way, keep the production seam (still useful, harmless, and testable once the module's Robolectric config is fixed) but document coverage as resting on on-device manual verification instead, same as this repo's `FavoritesSectionCardDecoration`/`FavoritesSectionFrameDecoration` precedent.
**First encountered:** Quality gate for `favorites-section-and-dialog-redesign` (DEF-2 title-color coverage attempt, gate-2, commit range `3550da6`..`e426147`).

---

## Engine update build fails with `Failed to find target with hash string 'android-37'` — SDK Platform 37 not yet installed

**Symptom**

The first `gradlew assembleDebugApp` (or any Gradle task touching `:openVpnEngine`) after bumping the `src/external/OpenVPNEngine` submodule fails at configuration time with:

```
Failed to find target with hash string 'android-37' in: <sdk-path>
```

**Root cause**

Upstream `ics-openvpn` moved the engine module's `compileSdk`/`targetSdk` from 36 to 37 (see `main/build.gradle.kts` in the engine submodule) while this client's own modules (`src/core`, `src/mobile`, `src/tv`) intentionally stay pinned at `compileSdk 36` — a per-module compileSdk mismatch that AGP allows. If Android SDK Platform 37 is not yet present in the local/CI SDK install, Gradle configuration fails before any compilation starts, because `:openVpnEngine` itself needs platform 37 to configure.

**Solution**

Re-run the same build. The Android Gradle Plugin auto-installs missing SDK platforms/build-tools referenced by any module's `compileSdk` as part of a retry when the SDK manager has network access and license acceptance is already satisfied — the first invocation triggers the platform 37 download, the second invocation (after it lands) configures successfully. If auto-install is disabled or the environment has no network access to the SDK manager, install it explicitly before building:

```bash
sdkmanager "platforms;android-37"
```

Treat "SDK Platform 37 available" as a build prerequisite whenever `src/external/OpenVPNEngine` is bumped to an upstream revision that raises the engine's `compileSdk`/`targetSdk`, even though the client app modules themselves stay on `compileSdk 36`.

**First encountered**

US-14 (`update-openvpn-engine`), engine submodule bump `a83da9ff -> 764b6b70`. First `gradlew assembleDebugApp` run failed with a stale SDK target list; the retry succeeded (`BUILD SUCCESSFUL in 49m 48s`) after the SDK manager installed platform 37 mid-build.

**Known gap (non-blocking, informational)**

The client's central version catalog (`src/gradle/libs.versions.toml`) does not track the engine's own catalog bumps that came in with this same upstream sync (bouncycastle 1.69→1.70, okhttp, kotlin, and others) because the client build resolves `libs.*` from its own catalog, not the engine's. A future story may be warranted to reconcile these and consider aligning client `compileSdk` to 37; no such story exists yet as of this writing.

**References**

- `src/external/OpenVPNEngine/main/build.gradle.kts` (engine `compileSdk`/`targetSdk`)
- `src/core/build.gradle.kts`, `src/mobile/build.gradle.kts`, `src/tv/build.gradle.kts` (client `compileSdk 36`, unchanged)
- the ClickUp story

---

## CI's bundled `sdkmanager` cannot resolve `platforms;android-37` even though Gradle can

**Symptom**

Explicitly adding `"platforms;android-37"` / `"build-tools;37.0.0"` to the `sdkmanager --sdk_root=... install` step in `.github/workflows/build-by-pull-request.yml` (attempting to make the SDK Platform 37 prerequisite above deterministic in CI) makes the "Install Android SDK packages" step fail outright:

```
Warning: Failed to find package 'platforms;android-37'
##[error]Process completed with exit code 1.
```

**Root cause**

The GitHub-hosted runner's bundled `sdkmanager` (cmdline-tools `16.0` as installed by `android-actions/setup-android@v3`) resolves packages against its own repository manifest, which does not yet list `platforms;android-37`/`build-tools;37.0.0` — passing an unresolvable package name to `sdkmanager` is a hard error (exit 1), unlike Gradle/AGP's own SDK auto-download path (`SdkComponentsBuildService`), which resolves against a separate, more current package index and successfully fetches platform 37 mid-build. That's why CI passed *before* this explicit-install attempt (relying on Gradle's implicit auto-download) and failed *after* it (forcing an explicit `sdkmanager` call that can't resolve the package).

**Solution**

Do not explicitly install `platforms;android-37`/`build-tools;37.0.0` via the CI `sdkmanager` step. Leave the workflow's SDK install step at `platforms;android-36` only (matching the client app modules' `compileSdk`) and let Gradle's own AGP auto-download resolve the engine module's `compileSdk 37` requirement during the build, the same implicit behavior documented in the local-build entry above. Revisit only once `platforms;android-37` is confirmed present in the runner image's `sdkmanager` repository listing (check with `sdkmanager --list` on a fresh runner), or if `android-actions/setup-android` bumps its bundled cmdline-tools version.

**First encountered**

US-14 (`update-openvpn-engine`), PR #123 round 2 — commit `387ec39` (added the explicit install, broke CI run `29689716964`); reverted in `f154ee9` back to the implicit-auto-download path.

**References**

- `.github/workflows/build-by-pull-request.yml` ("Install Android SDK packages" step)
- CI run `29689716964` (job "Build Debug APKs", step "Install Android SDK packages")
- The AS-1/AS-3 disposition came from the US-14 quality gate, whose evidence lived under `.sdlc/` —
  gitignored runtime state, so it is not retrievable from a checkout. The conclusion is stated above.

---

## Removing an enum constant silently deletes regression coverage that a mechanical find/replace doesn't restore

**Symptom**

While removing `ServerSource.LEGACY`/`ServerSource.CUSTOM`, every test that used those constants as
fixtures compiled cleanly after a mechanical substitution (`CUSTOM` → `VPNGATE`, `LEGACY` →
`DEFAULT_V2`), but 3 tests were deleted outright rather than substituted because their premise
depended on a property only the removed values had (two distinct non-empty URL lists on one enum
value, or a third enum value distinct from both remaining ones). The deletions were easy to miss:
the test suite still passed at 100%, and nothing in the diff *looked* wrong — a shrinking test count
doesn't fail a build the way a compile error does.

**Root cause**

A concurrency-guard test and a cache-fallback test both needed the mid-operation value to differ
from both the "before" and "after" values in a 3-way comparison — impossible to construct once the
enum only has 2 values, since substituting one still-existing value produces a scenario that's
either a no-op or unreachable. A URL-filter test similarly relied on `CUSTOM`'s ability to hold an
arbitrary user-entered URL to exercise placeholder-host/non-HTTPS rejection; with `CUSTOM` gone, no
remaining source produces an arbitrary URL, so the code path being tested (`isUsableServerUrl`)
still exists and runs for `VPNGATE`, but had zero direct test coverage after the mechanical pass.

**Solution**

Code review (not the initial implementation, and not the automated test run) is what caught this,
by diffing the pre- and post-removal test file to look for tests removed by name rather than
substituted in place, and by tracing each affected production code path (the concurrency guard, the
cache-only fallback, `isUsableServerUrl`) to confirm it was still live and reachable for the
remaining enum values despite losing its dedicated test. Two of the three gaps were closed by
re-adding equivalent tests substituting a remaining enum value for the removed one, manufacturing
the differentiating condition directly (e.g. writing a synthetic stale cache-key entry) rather than
relying on the enum to provide it. The third (concurrency-guard tests) was restored but the quality
gate flagged that the restored version is now unfalsifiable — it passes identically whether the
guard exists or not, since the 2-value enum can no longer construct a scenario that distinguishes
"guard present" from "guard absent." This residual gap was accepted as a known, documented
limitation rather than blocking the release, since fixing it would require redesigning the guard's
test harness independent of `ServerSource`'s cardinality.

**Takeaway**

When removing an enum constant (or any fixture value) from a codebase, treat "test count decreased"
as a signal requiring the same scrutiny as a compile error, not as a natural side effect of a smaller
enum. Specifically check whether a deleted test's premise depended on a *property* unique to the
removed value (a third distinct state, an arbitrary/user-controlled value, a second URL) rather than
just the value's name — mechanical substitution only works when the remaining values can reproduce
the same differentiating property.

**First encountered**

US-15 (`remove-legacy-and-custom-server-sources`) code review (iteration 1) and the follow-up
quality gate — findings on `ServerSelectionSyncCoordinatorTest.kt` (concurrency guard),
`ServerRepositoryTest.kt` (cache-only stale-key fallback), and `UserSettingsStoreTest.kt`
(`isUsableServerUrl`).

**References**

- `src/core/.../servers/ServerSelectionSyncCoordinator.kt` (the concurrency guard, ~lines 90-96)
- `src/core/.../servers/ServerRepository.kt` (`getServersWithOutcome`'s cache-only branch,
  `readLastCache`)
- `src/core/.../settings/UserSettingsStore.kt` (`isUsableServerUrl`)
- commits `c2f1266` (concurrency-guard + `isUsableServerUrl` tests restored),
  `e427d55` (cache-only stale-key test restored)

---

## Auto-switch never fires when the live AIDL push status callback stalls — bug 86cb21563

**Symptom**

Field report: app stuck showing "Connecting..." indefinitely with no automatic server switch.
Confirmed from a user-supplied logcat capture (`logcat_20260804_145212.txt`): the VPN connected
normally for ~8 minutes, then the engine reported `LEVEL_CONNECTING_NO_SERVER_REPLY_YET`
(`CONNECTRETRY`/`TCP_CONNECT` alternating) — a stall that, everywhere else in the same log,
triggers a switch within 5-8 s. This one time it never did; the app sat on
`LEVEL_CONNECTING_NO_SERVER_REPLY_YET` for 5+ minutes until the capture ended, zero switch
activity.

**When it occurs**

The engine's live AIDL push callback (`updateStateString`, binder thread) stalls — stops being
invoked — while the app is in a CONNECTING-family state. The app's own periodic snapshot-poll
fallback (`OpenVpnService.applyStatusSnapshot()`) kept the UI accurate throughout, which is why
the bug was easy to miss on a quick glance: nothing looked frozen except the missing switch.

**Root cause**

`OpenVpnService.applyStatusSnapshot()` hardcoded `syncEngineState(level, snapshot.state,
allowAutoSwitch = false)`. This poll path is meant to be a fallback that keeps
`ConnectionStateManager` in sync when the live push channel goes quiet, but the hardcoded `false`
meant it could never wake `ServerAutoSwitcher` — only the live push path could start the
auto-switch timeout timer. When the live push channel stalled, the poll fallback covered the UI
but not the auto-switch responsibility, so the timer never started and the app hung forever.

**Fix applied**

Compute the flag instead of hardcoding it: `allowAutoSwitch = !isAidlFresh()`, reusing the
freshness predicate already shared by three other consumers in the same file (see
`docs/features/server-sync.md`, "Status Sync: Live AIDL Push vs. Snapshot-Poll Fallback"). When
the live push channel is fresh, behavior is unchanged (`allowAutoSwitch=false`, no duplicate
triggers). When it has gone stale, the poll path takes over driving auto-switch. Follow-up fix
cycle (code review round 1) additionally made `lastLiveStatusMs`/`lastStatusSnapshotMs`
`@Volatile` for correct cross-thread visibility between the binder-thread writer and the
main-thread reader.

**Key diagnostic technique: use the throttled "Suppressed N logs" summary as evidence for whether `onEngineLevel` ran**

The investigation's turning point was building the case that `ServerAutoSwitcher.onEngineLevel`
was never called during the incident window — from a **release-build** logcat, where the direct
evidence is normally invisible. `ServerAutoSwitcher`'s own per-call logging uses
`AppLog.dThrottled(...)` (`DEBUG` priority), and `AppReleaseTree.log()`
(`src/core/.../logging/AppLogTrees.kt`) drops `DEBUG`/`VERBOSE` from release logcat entirely:

```kotlin
if (priority == Log.DEBUG || priority == Log.VERBOSE) return
```

So the individual "Switch wait: Ns" debug lines are simply absent from a release capture — a naive
`grep ServerAutoSwitcher` shows nothing and looks exactly like "the switcher was never touched" and
"the switcher was touched but not logged," which are indistinguishable from the raw absence alone.

The resolving insight: `AppLog.logThrottled()` always emits its periodic **suppressed-count
summary** at `Log.INFO` priority regardless of the throttled call's own priority:

```kotlin
log(priority = Log.INFO, tag = tag, message = "Suppressed $suppressed repeated logs for key=$key", ...)
```

`Log.INFO` passes the `AppReleaseTree` filter unconditionally, but the summary line only appears
after at least one suppressed call is followed by a later call that flushes the counter — a timer
that reaches its threshold and fires on its very first attempt can legitimately produce neither,
even when `onEngineLevel` ran correctly. So in a release logcat, grepping for `"Suppressed"` under
the `ServerAutoSwitcher` tag gives useful but not by itself conclusive evidence: if `onEngineLevel`
(and therefore the throttled "Switch wait" log) had fired repeatedly with more than 30 s between
throttle-window closes during the incident window, a `Suppressed N repeated logs for
key=switch-wait-...` line would appear. Its total absence across the entire multi-minute incident
window did not by itself prove the auto-switch timer never started; combined with the poll-path
cross-check below — confirming the window was long and active enough that a real, repeated
invocation would have left some trace — it supported the conclusion that the timer never started
at all, rather than having started and being merely under-logged.

**Commands used**

```bash
grep -c "ServerAutoSwitcher" logcat_20260804_145212.txt              # sanity: tag exists at all
grep "ServerAutoSwitcher" logcat_20260804_145212.txt | grep -i "suppressed"   # absence here is inconclusive alone
grep "OpenVpnService" logcat_20260804_145212.txt | grep -i "AIDL\|snapshot"   # cross-check: poll path still logging (UI stayed accurate)
```

This technique generalizes to any throttled log (`AppLog.dThrottled`/`AppLog.iThrottled`) whose
underlying priority is filtered out of release builds: the throttle-summary line is always `INFO`
and survives `AppReleaseTree`, so it can stand in for the filtered-out per-call log when you need
to answer "did this code path ever run" from a release-build capture. See the companion entry in
`docs/guides/how-to.md` ("Diagnose whether a throttled DEBUG log path ever fired, from a
release-build logcat").

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
  (`applyStatusSnapshot`, `isAidlFresh`)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ServerAutoSwitcher.kt`
  (`onEngineLevel`, throttled "Switch wait" log)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/logging/AppLog.kt`
  (`logThrottled`, suppressed-count summary)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/logging/AppLogTrees.kt`
  (`AppReleaseTree.log`, DEBUG/VERBOSE filter)
- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnServiceStatusSyncTest.kt`
- `docs/features/server-sync.md` ("Status Sync: Live AIDL Push vs. Snapshot-Poll Fallback")
- ClickUp [86cb21563](https://app.clickup.com/t/86cb21563); commits `f909d31`, `07bcaae`
- `docs/qa-evidence/bug-autoswitch-stale-push-stall-gate-1.md` -- **not committed** (path is
  gitignored, local-only quality-gate evidence); see ClickUp task
  [86cb21563](https://app.clickup.com/t/86cb21563) for the actual evidence

---

## OpenVpnService `RemoteServiceException` (`ForegroundServiceDidNotStartInTimeException`) on reconnect after a background status sync — bug 86cb35fbt

**Status: RESOLVED** — commit `ce2f952`, ClickUp [86cb35fbt](https://app.clickup.com/t/86cb35fbt)

**Symptom**

`OpenVpnService` crashed roughly 5 seconds after a VPN reconnect with:

```
android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException
```

It only happened when the `OpenVpnService` instance handling the reconnect had earlier been
created by a passive `ACTION_SYNC_STATUS` status sync (`context.startService()`, not
`startForegroundService()`) rather than a genuine `ACTION_START` — e.g. the app returning to the
foreground and syncing status while a connection was already active/connecting, followed shortly
after by a real connect/reconnect on that same still-alive service instance.

**Root cause**

`OpenVpnService.enterControllerForeground()` had an early-return guard:

```kotlin
private fun enterControllerForeground(stopOnFailure: Boolean = true): Boolean {
    if (controllerForegroundActive) return true
    // ... Service.startForeground() call below, skipped when the guard returns early
}
```

`onCreate()` eagerly calls `enterControllerForeground(stopOnFailure = false)` (the 8b2a778 fix,
previous entry above), which sets `controllerForegroundActive = true` and posts the notification
regardless of which action triggered creation. The `ACTION_SYNC_STATUS` handler only calls
`exitControllerForeground()` when the VPN is `DISCONNECTED` — so an instance created by
`ACTION_SYNC_STATUS` while a connection was active/connecting kept
`controllerForegroundActive = true`.

When a genuine `ACTION_START` later arrived on that same instance (via
`ContextCompat.startForegroundService()`, per `VpnManager.startVpn()`), Android's
ActivityManagerService starts a **new, independent 5-second foreground-service-start timer** for
that specific `startForegroundService()` call — regardless of any earlier `startForeground()` call
made for a different triggering intent. `enterControllerForeground()`'s early-return guard skipped
the fresh `Service.startForeground()` call needed to satisfy *this* timer (it saw
`controllerForegroundActive` already `true` from the earlier `ACTION_SYNC_STATUS`-triggered
`onCreate()` and returned immediately). The timer expired ~5 s later with no matching
`startForeground()` call registered against it, and Android killed the process.

**Relationship to the earlier crash (8b2a778):** same exception class, same service, same
underlying "app must call `startForeground()` within 5 s of `startForegroundService()`"
constraint — but a different trigger path. The 8b2a778 fix closed a race in `onCreate()` itself;
this fix closes a gap where a *later*, unrelated promotion to foreground (from
`ACTION_SYNC_STATUS`) could cause a *subsequent* genuine `ACTION_START` to skip its own required
`startForeground()` call.

**Fix applied**

Removed the early-return guard so `enterControllerForeground()` always (re)issues
`Service.startForeground()`, regardless of `controllerForegroundActive`'s prior state:

```kotlin
private fun enterControllerForeground(stopOnFailure: Boolean = true): Boolean {
    // Always (re)issue Service.startForeground() below, even if controllerForegroundActive is
    // already true. A genuine ACTION_START must get a fresh startForeground() call to satisfy
    // Android's foreground-service-start timing requirement, regardless of any prior
    // controllerForegroundActive state left over from an earlier ACTION_SYNC_STATUS-triggered
    // onCreate() call. Repeated/redundant startForeground() calls are idempotent and Android-
    // supported (they just (re)show/update the notification), so this is safe.
    ...
}
```

Repeated `startForeground()` calls are safe/idempotent on Android — they just (re)post or update
the existing notification under the same ID — so unconditionally reissuing the call closes the gap
without any behavior change for the already-working paths.

**Evidence**

- Regression test `OpenVpnServiceNotificationTest.startActionCallsStartForegroundAgainEvenWhenControllerForegroundAlreadyActive`:
  reproduces an `ACTION_SYNC_STATUS`-created instance with the VPN left active (so
  `exitControllerForeground()` is not called), asserts `controllerForegroundActive` is still `true`
  as a precondition, clears the notification, then asserts a subsequent `ACTION_START` re-posts it.
- Real-device manual QA: 19 reconnect cycles across all trigger shapes (user-initiated foreground
  toggles, auto-switch/no-reply-timer cycles, backgrounded reconnects, airplane-mode network-drop
  cycles) on Samsung Galaxy A71 (R58N849XQEY) at commit `9032cf934106385fb4b3b63a1005641a0c153244`.
  Full-session logcat (222,832 lines) scanned for `ForegroundServiceDidNotStartInTimeException`,
  `RemoteServiceException`, `FATAL EXCEPTION`, `AndroidRuntime` crash, and ANR: zero matches. The
  exact vulnerable pattern (`ACTION_START` following `ACTION_SYNC_STATUS` on the same live
  instance) was confirmed to have fired repeatedly during the soak (`ACTION_START`=16,
  `ACTION_SYNC_STATUS`=17, full service teardown=27) without reproducing the crash.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
  (`enterControllerForeground`)
- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnServiceNotificationTest.kt`
- ClickUp [86cb35fbt](https://app.clickup.com/t/86cb35fbt); commit `ce2f952`
- See also the earlier, related crash above: [`RemoteServiceException`: `startForegroundService()`
  did not call `startForeground()` — crash on first VPN connect after APK
  update](#remoteserviceexception-startforegroundservice-did-not-call-startforeground--crash-on-first-vpn-connect-after-apk-update)
