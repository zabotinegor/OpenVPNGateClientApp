# Known Issues and Solutions

This runbook collects non-obvious problems and their solutions discovered during development and
QA. Add an entry only when the issue is likely to recur and the fix is not obvious from the
error message alone.

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
- `docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-05-fix-broken-instrumented-tests.md`

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
- `docs/userstories/BUG-foreground-service-crash-after-update.md`

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
- `docs/userstories/MP-20260623-sse-reliability-fixes/SUB-03-client-reconnect-correctness.md`

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
- `docs/userstories/MP-20260623-sse-reliability-fixes/SUB-03-client-reconnect-correctness.md`

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

### Favoriting a legacy Server by id will collide across servers

**Context:** implementing server-favorite UI on top of `FavoritesStore`/`FavoritesFilter` (SUB-02/SUB-03 of MP-20260706-favorite-countries-servers).

**Problem:** `Server.id` (`src/core/.../servers/Server.kt`) defaults to `0` and is only populated with a real value by `ServerV2.toLegacyServer()`. When `UserSettingsStore.load(ctx).serverSource` is `LEGACY`, `VPNGATE`, or `CUSTOM` (not `DEFAULT_V2`), every `Server` in the list keeps `id == 0`. Favoriting one such server marks all of them as favorited under `FavoritesStore`, since favorites are keyed purely by `Server.id`.

**Solution:** before wiring server-favorite UI, either (a) restrict server-favoriting to `DEFAULT_V2` source only, or (b) extend the favorite key to a composite (e.g. `ip` + `configData`, mirroring how `SelectedCountryStore.ensureIndexForConfig` matches servers) so non-V2 sources don't collide. Flagged as non-blocking for SUB-01 (data-layer-only, no consumer yet) but must be resolved as part of SUB-02/SUB-03.

**Commands/code:** n/a — design note, not a runtime fix.

### Country-code comparisons: case-sensitive in FavoritesStore/FavoritesFilter, case-insensitive elsewhere

**Context:** `FavoritesStore`/`FavoritesFilter` (SUB-01) compare favorite country codes with plain string equality, while `CountryServersInteractor.getServersForCountryV2()` (`src/core/.../servers/CountryServersInteractor.kt:64`) matches country codes with `equals(ignoreCase = true)`.

**Problem:** if a backend country code ever differs in casing between calls (unlikely today, but the rest of the codebase treats codes as case-insensitive), favorite filtering could silently drop a match that other code paths would accept.

**Solution:** no fix applied in SUB-01 (backend codes are stable today, no AC requires it). Recommended as an optional hardening item when SUB-02/SUB-03 touch this code — align `FavoritesFilter`'s country-code comparison to `ignoreCase = true` for consistency.

**First encountered**

SUB-01 (`FavoritesStore.kt`, `FavoritesFilter.kt`) — MP-20260706-favorite-countries-servers, flagged during code review and quality gate.

**References**

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesStore.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesFilter.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/CountryServersInteractor.kt:64`
- `docs/qa-evidence/favorites-data-layer-review-1.md`, `docs/qa-evidence/favorites-data-layer-gate-1.md`
