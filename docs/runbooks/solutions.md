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
