# SUB-02 Docs Audit Report

**Story:** `docs/userstories/MP-20260621-server-push-sse/SUB-02-android-sse-client.md`
**Branch:** `feature/MP-20260621-server-push-sse`
**Date:** 2026-06-23
**Author:** docs-maintainer subagent

---

## Files Audited

| File | Status |
|---|---|
| `src/docs/server-sync-flow.md` | Updated |
| `CLAUDE.md` | Updated |
| `docs/runbooks/android-qa.md` | Updated |
| `docs/runbooks/how-to.md` | Updated |
| `docs/runbooks/solutions.md` | Updated |

---

## Changes Made

### 1. `src/docs/server-sync-flow.md`

**Added:** SSE trigger row to the Trigger Matrix table:

> `SSE server-changed push event` | `SseServerEventsClient.kt` | `forceRefresh=true`, `cacheOnly=false`. Fires immediately on `servers-changed` event.

**Added:** New "SSE Server-Push Sync (SUB-02)" section covering:
- Foreground/background lifecycle via `ProcessLifecycleOwner`
- Exponential backoff behavior (5 s initial, 5 min cap)
- OkHttp child-client isolation (`readTimeout(0)` on a copy, not the shared singleton)
- Endpoint derivation from `PRIMARY_SERVERS_URL` via `PrimaryDomainRoutes.sseServersEventsUrl()`
- Koin wiring in `CoreDi.kt` and `CoreApp.registerSseLifecycleObserver()`
- `okhttp-sse` version-pinning requirement

### 2. `CLAUDE.md`

**Added:** `SseServerEventsClient.kt` row to the Key Entry Points table:

> SSE client — foreground-only long-poll; triggers server sync on `servers-changed` push event

### 3. `docs/runbooks/android-qa.md`

**Added:** "MP-20260621 SUB-02 — Android SSE Client for Server-Push Notifications" section with:
- Logcat tag table (`OpenVPNGateApp:SseServerEventsClient`, `OpenVPNGateApp:CoreApp`)
- ADB commands: stream SSE logs, confirm connection open/close, verify servers-changed event, monitor downstream sync, detect backoff retries, check for fatal errors
- Step-by-step manual QA procedure (install, launch, verify open, background, verify close, foreground again, verify event-triggered sync)

### 4. `docs/runbooks/how-to.md`

**Added:** "Verify SSE client connection on device" section covering:
- When to use (post-deploy or when debugging real-time update issues)
- Step 1: logcat filter setup before launch
- Step 2: launch via SplashActivity and expected logcat sequence
- Step 3: foreground/background lifecycle verification with expected log lines
- Step 4: event-triggered sync verification
- Backoff diagnosis (expected log pattern when endpoint is unreachable)
- References to source files, other runbooks, and the user story

### 5. `docs/runbooks/solutions.md`

**Added:** Three new entries documenting non-obvious SSE implementation decisions:

**a) SSE long-poll times out: `readTimeout(0)` required on a child OkHttp client**
- Root cause: OkHttp default read timeout terminates long-poll connections between events
- Solution: `okHttpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()` on a child
  copy; the shared singleton is not mutated
- Code reference: `SseServerEventsClient.connectOnce()`

**b) `okhttp-sse` must be pinned to the same version as the main `okhttp` dependency**
- Root cause: `okhttp-sse` and `okhttp` share internal classes; version mismatch causes classpath conflicts
- Solution: Use `version.ref = "square-okhttp"` for the `okhttp-sse` catalog entry
- Code reference: `src/gradle/libs.versions.toml`, `src/core/build.gradle.kts`

**c) `ProcessLifecycleOwner` must be registered from the main thread after `startKoin`**
- Root cause: `Lifecycle.addObserver()` is main-thread-only; Koin must be initialized first
- Solution: Call `registerSseLifecycleObserver()` directly in `Application.onCreate()` after
  `startKoin` returns (not on a background coroutine); wrap in `runCatching`
- Code reference: `CoreApp.registerSseLifecycleObserver()`

---

## Other Docs Checked

- `README.md` — No server-sync-flow references; no update needed.
- `AGENTS.md` — Agent governance doc; no SSE-specific content needed.
- `src/docs/logging-policy.md` — SSE logging follows existing policy (Timber, `AppLog.d/i/w`); no update needed.
- `tests/manual-e2e/README.md` — E2E test structure doc; SSE QA steps now documented in `android-qa.md`; no structural change needed.

---

## QA Evidence Source

SSE client tested on Samsung Galaxy A71 SM-A715F, Android 13 (ADB serial R58N849XQEY).
Backend endpoint: `https://openvpngateclientgate.azurewebsites.net/api/v1/servers/events`.
SSE connection opened (HTTP 200), `servers-changed` event received and sync triggered successfully.
See `docs/qa-evidence/sub-02-manual-qa.md` for full QA evidence.

---

## Verdict

All targeted documentation updated. No blocking issues found.
