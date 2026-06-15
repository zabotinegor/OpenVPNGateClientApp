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

## `MainActivitySmokeTest` failures: `NoActivityResumedException` on real device

**Symptom**

All (or most) cases in `MainActivitySmokeTest` fail with `NoActivityResumedException` when run
via `./gradlew connectedDebugAndroidTestApp`. The activity never resumes within the Espresso
timeout window.

**Root cause**

`ActivityScenario` in `MainActivitySmokeTest` launches `MainActivity` directly, bypassing
`SplashActivity`. The production `MainActivity` assumes that `SplashActivity` has already
completed its server-preload network calls (managed via `SplashServerPreloadInteractor`). When
those OkHttp calls are absent, the Espresso `OkHttpIdlingResource` never becomes idle, causing
the test runner to time out before the activity enters a resumed state.

**Affected device**

Confirmed on Samsung Galaxy A71 SM-A715F, Android 13 (ADB serial R58N849XQEY). Fails
identically on `dev` branch without any feature-branch changes, confirming it is a pre-existing
issue.

**Workaround**

For manual smoke verification, always launch the app through `SplashActivity`:

```bash
adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
```

Do not rely on `MainActivitySmokeTest` as a CI gate until the test is refactored to either:
- mock the network layer and OkHttp idling resource, or
- launch `SplashActivity` as the entry point and navigate forward from there.

**First confirmed**

SUB-02 docs cycle (2026-06-15). Same failure reproduced on `dev` branch (7/7 cases).

**References**

- `src/mobile/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/mobile/MainActivitySmokeTest.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashServerPreloadInteractor.kt`
