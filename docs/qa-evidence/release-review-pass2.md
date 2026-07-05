# Release Review — Pass 2 (Coverage, Security/Performance, Final Disposition)

**Branch:** `feature/release/04.07.2026`
**Diff scope:** `origin/main...HEAD`
**Builds on:** `docs/qa-evidence/release-review-pass1.md` — carrying forward the 4 risk-register items, all resolved as non-blocking in Pass 1.

## Test coverage verification

- `SseServerEventsClientTest.kt` (new, 1026 lines, 30 `@Test` methods, Robolectric + `MockWebServer`): covers pure backoff math, URL rotation after `urlFailureThreshold`, debounce collapsing of rapid `servers-changed` bursts, `onOpen`/`onFailure`/`onClosed` transitions, stable-connection backoff reset, and `start()`/`stop()` idempotency.
- `MainConnectionInteractorTest.kt`: 4 new tests directly targeting the counter-reset fix (`prepareStart_null_server_returns_null`, `..._reads_fresh_config_from_store`, `..._falls_back_to_selected_server_config_when_store_empty`, `..._ignores_store_when_preferUserSelection_false`) — matches the code path added in `MainConnectionInteractor.kt` line-for-line (fresh-store read gated by `preferUserSelection`, fallback on blank/null).
- `MainSelectionInteractorTest.kt` / `MainViewModelTest.kt`: updated for the config-first index resolution and `pendingUserSelectionOverride`/`isBackgroundRefresh` guard — consistent with Pass 1's traced call sites.
- `OpenVpnServiceDisconnectProbeTest.kt` (new) and `OpenVpnServiceNotificationTest.kt`, `ServerAutoSwitcherTest.kt`: cover the hardprobe-on-disconnect and FGS-notification-guard additions.
- No coverage gap identified for the integration seams called out in Pass 1 (SSE→sync→selection, FGS-guard vs. hardprobe). Full suite: **651 tests, 0 failures** (re-confirmed from `core/build/test-results/testDebugUnitTest/*.xml` after Pass 1's build run — no flakiness observed on a clean re-check of aggregated XML).

## Security / performance / battery review

- **Network permissions**: `INTERNET` and `ACCESS_NETWORK_STATE` already declared in `src/core/src/main/AndroidManifest.xml` (pre-existing) — no new manifest permission required for the SSE long-poll client. No permission-scope regression.
- **Foreground-only SSE**: `SseServerEventsClient` is a `DefaultLifecycleObserver` registered against `ProcessLifecycleOwner` (`CoreApp.registerSseLifecycleObserver()`), so the long-poll connection is torn down on `onStop` (app backgrounded) and re-established on `onStart`. This bounds battery/data impact to foreground time only, consistent with the class's own doc comment. `stop()` fully cancels the `EventSource`, the reconnect job, and the `CoroutineScope` (`clientScope?.cancel()`), so no leaked long-poll connection or coroutine survives backgrounding.
- **Thread safety of URL rotation**: `currentUrlIndex` / `failuresOnCurrentUrl` mutations in `onFailure` are wrapped in `synchronized(this@SseServerEventsClient)` guarded by `running.get()`, matching the same lock object used by `start()`/`stop()`. This prevents the documented race (a stale connection's `onFailure` firing after `stop()` has already reset counters). Verified by reading the lock usage across all three sites (`start`, `stop`, `onFailure`) — consistent locking discipline, no lock-ordering inversion.
- **No secrets/URLs hardcoded**: `sseServersEventsUrl()` derives from `PRIMARY_SERVERS_URL` (build-time property), matching `AGENTS.md`'s "never hardcode production URLs" rule. The one literal URL in the codebase (`https://openvpnclientgate.local/...`) is an intentionally-inert fallback placeholder, not a real endpoint (already flagged as non-issue in Pass 1).
- **Logging**: `SseServerEventsClient` uses `AppLog` (Timber-backed) exclusively, no `android.util.Log` usage — compliant with `src/docs/logging-policy.md`. Reviewed log statements for PII/secret leakage: only logs event type, HTTP status codes, and URLs (server URLs, not credentials) — acceptable per policy.
- **R8/minify**: `isMinifyEnabled=true`/`isShrinkResources=true` in mobile/tv release variants are unchanged (verified no diff touches those blocks). The new `consumer-rules.pro` keep rule is scoped only to `ProbeApi` and does not weaken shrinking elsewhere. Recommend (non-blocking, carry to Manual QA) a release-variant smoke pass exercising SSE connect/backoff/reconnect, since this is the first minified build shipping `okhttp-sse` — the QA evidence trail (`docs/qa-evidence/`) for the individual stories does not show an explicit release-build SSE check.

## Cross-story regression sweep (final)

Re-walked all 4 stories' file sets together for shared-state collisions:

1. `d3ca0ab` (SSE client) + `9dd3004` (server-list reliability): both touch `ServerSelectionSyncCoordinator` consumers but through different entry points (SSE push vs. WorkManager pull) — coordinator itself is unchanged in this diff (not in the changed-files list), so no coordinator-level regression surface.
2. `9dd3004` + `9ef8475` (FGS crash / ProbeApi type erasure): both touch `OpenVpnService.kt`; diffed together in Pass 1 and confirmed disjoint state writes.
3. `9ef8475` + `6aa4b33` (counter reset): no shared files.
4. `6aa4b33` + SSE (`d3ca0ab`): shared consumer is `MainViewModel`/`MainConnectionInteractor` selection state — confirmed safe in Pass 1 via the `pendingUserSelectionOverride`/fresh-store-read dual guard.

No new blocking cross-story interaction found in Pass 2 beyond what Pass 1 already verified as safe.

## Agent-sync tooling commit — final sanity check

Re-confirmed `a0a97d4` and the tooling files carried in the other 4 commits touch only: Claude Code hook/settings config, git hooks (`pre-commit`/`pre-push`), agent-sync scripts, `AGENTS.md`/`AGENTS/*.md` governance docs, and `.gitignore`. Zero touches to `src/**` (app code), `src/*/build.gradle.kts`, manifests, or CI-relevant build scripts. **Confirmed config-only, no functional app impact**, matching the task brief's characterization.

## Final risk register

| # | Area | Severity | Disposition |
|---|------|----------|-------------|
| 1 | SSE-triggered sync vs. selection override | Medium | Resolved — dual-guard verified, test-covered |
| 2 | FGS guard vs. hardprobe enqueue ordering | Medium | Resolved — disjoint state, test-covered |
| 3 | Release-variant (minified) SSE smoke test | Low | Non-blocking — recommend for Manual QA step, not a review blocker |
| 4 | Tooling commit scope broader than `a0a97d4` alone | Info | Confirmed config-only across all commits |

**No blocking findings across either pass.**

## Action plan carried to Quality Gate

- Carry item #3 (release-variant SSE smoke check) into Manual QA scope as a recommended (non-blocking) check.
- No code changes required before Quality Gate.
