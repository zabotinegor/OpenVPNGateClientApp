# Release Quality Gate — Iteration 1

**Branch:** `feature/release/04.07.2026`
**Diff scope:** `origin/main...HEAD` (5 commits: `d3ca0ab`, `9dd3004`, `9ef8475`, `6aa4b33`, `a0a97d4`)
**Diff size:** 95 files changed, +6153/-131
**Builds on:** `docs/qa-evidence/release-review-pass1.md`, `docs/qa-evidence/release-review-pass2.md` (code review, 0 blocking findings)

## Verification run

- `./gradlew testDebugUnitTestApp` from `src/` on current HEAD (`a0a97d4`): **BUILD SUCCESSFUL**.
- Aggregated `core/build/test-results/testDebugUnitTest/*.xml`: **651 tests, 0 failures, 0 errors** (80 test-result XML files). Matches the count already reported by both review passes — confirms no drift between review and gate runs, same commit.

## Findings (severity-ordered)

### 1. [Low, non-blocking] `syncEngineState()` `reconnectPending` FGS guard has no unit test

`OpenVpnService.syncEngineState()` (diff lines ~1713-1738) adds `reconnectPending = idleLevel && (reconnectingHint || userInitiatedStart)` to suppress `exitControllerForeground()` at `LEVEL_NOTCONNECTED`/`LEVEL_NONETWORK`. This is the core fix for the FGS `RemoteServiceException` crash (2026-06-25) described in the inline comment. No unit test in `OpenVpnServiceNotificationTest.kt` (or elsewhere in `src/core/src/test`) drives `syncEngineState`/`updateStateString` with `LEVEL_NOTCONNECTED` while `userInitiatedStart=true` or `ConnectionStateManager.reconnectingHint.value=true` and asserts `controllerForegroundActive` stays `true` / `exitControllerForeground()` is not invoked. Existing tests (`syncStatusActionExitsControllerForegroundWhenDisconnected`, `syncStatusActionDoesNotExitControllerForegroundWhenVpnActive`, `syncEngineState_clearsUserInitiatedStart_onLevelConnected`) cover adjacent but not this exact branch.
- **Mitigation:** manual E2E specs exist and target this exact scenario at the device level — `tests/manual-e2e/stories/bug-fgs-crash-rapid-reconnect-and-probe-type-erasure/cases/MQ-BUG-RRC-001-rapid-reconnect-no-crash.md` and `MQ-BUG-RRC-003-disconnect-reconnect-stability.md`. Unit coverage of this specific branch would still be worth adding as debt, but it is not a release blocker given the device-level evidence trail.
- **Recommendation:** track as a follow-up unit-test addition; not required before this release.

### 2. [Low, non-blocking] `hydrateStoredSelectionFromV2` config-mismatch→IP-fallback branch untested

`MainSelectionInteractor.kt` lines 168-179 (the `when` block resolving `selectedIndex`) has three branches: config match, config-non-blank-but-no-match falling back to IP match, and IP-only. `MainSelectionInteractorTest.kt` covers "config matches" (`hydration_selects_correct_server_by_config_when_all_share_same_ip`) and "config non-blank, both config and IP fail → index 0" (`hydration_no_ip_match_selects_first_server`), but no test exercises "config non-blank, config match fails, IP match succeeds" — i.e., the exact dynamic-configData scenario that was the root cause of `bug-server-counter-resets-on-connect`, reached via the *hydration* path rather than the Connect-time path.
- **Mitigation:** the Connect-time fix (`MainConnectionInteractor.prepareStart` re-reading the store fresh) is the primary defense for the original bug and is directly tested (`MainConnectionInteractorTest.kt`, 4 tests). This hydration branch is a secondary/startup-time code path with the same fallback logic; a gap here is lower risk than a gap in the Connect-time path.
- **Recommendation:** add one test case (`hydration_config_mismatch_falls_back_to_ip_match`) as follow-up debt; not blocking.

### 3. [Info] SSE client URL rotation is exercised only in tests, not reachable in production wiring

`SseServerEventsClient.defaultSseUrls()` (used by the only production DI wiring in `CoreDi.kt:166`) derives candidates solely from `PrimaryDomainRoutes.sseServersEventsUrl(PRIMARY_SERVERS_URL)`, explicitly excluding `FALLBACK_SERVERS_URL` because it points at the VPN Gate CSV endpoint (not SSE-capable) — this is documented in the code comment. In production this yields a single-entry URL list, so the exponential-backoff/URL-rotation code (tested extensively — 30 tests in `SseServerEventsClientTest.kt`) never actually switches to a second live endpoint; it only ever cycles back to the same URL. This is a partial mismatch with `SUB-05-client-fallback-sse-url.md` AC2 ("fallback URL is derived from FALLBACK_SERVERS_URL... to ensure parity"), which is not satisfiable given FALLBACK_SERVERS_URL's actual protocol shape.
- **Impact:** no functional regression — on an outage of the primary SSE endpoint, the client retries with backoff up to 5 minutes and the existing WorkManager periodic refresh remains the real safety net (as already noted in both review passes). This is a product/story-completeness note, not a code defect.
- **Recommendation:** no gate action required; flag to product/BA that SUB-05's stated multi-endpoint fallback is not realized in the current backend topology, in case a future release adds a genuinely independent secondary SSE domain.

### 4. [Info] R8/minified SSE smoke test — carried from review, unchanged disposition

Repeating from `release-review-pass2.md` risk #3: no release-variant (`isMinifyEnabled=true`) smoke test specifically exercises the new `okhttp-sse` dependency end-to-end. Already flagged as a non-blocking recommendation for Manual QA scope, not a quality-gate blocker (R8 keep rules are otherwise sound; `ProbeApi` keep rule is correctly scoped and doesn't weaken shrinking elsewhere).

## Coverage adequacy by layer

| Layer | Adequacy | Notes |
|---|---|---|
| Unit | Adequate | 651 tests green; new/changed logic (SSE client, counter-reset fix, FGS guard structure, hardprobe enqueue) has direct unit tests. Two narrow branch gaps noted above (#1, #2), both non-blocking given mitigating coverage elsewhere. |
| Integration/component | Adequate | Cross-story integration points (SSE→sync→selection guard, FGS-guard vs. hardprobe ordering) verified by code reading in review passes 1-2; no dedicated integration test harness exists in this repo for cross-component flows, consistent with existing project conventions. |
| Manual/E2E | Adequate, pending execution confirmation | Specs exist for all 4 stories (`tests/manual-e2e/stories/**`) including the exact FGS-crash scenario (#1 above). This gate does not re-run manual E2E — that is the next SDLC step (`manualQa`). |

## Security / performance

No new findings beyond `release-review-pass2.md` (network permissions unchanged, foreground-only SSE lifecycle correctly torn down, thread-safe URL rotation via `synchronized`, no hardcoded production URLs, Timber-only logging with no PII/secret leakage, minify/shrink settings preserved).

## Residual risks

- Two low-severity unit-test coverage gaps (branch-level, not path-level) — tracked as follow-up debt, not release blockers.
- SSE fallback-URL story intent (SUB-05 AC2) not fully realized in production topology — informational, no functional regression, WorkManager remains the real safety net.
- Release-variant SSE smoke test recommended at Manual QA (carried forward, non-blocking).

## Disposition

**No blocking findings.** All identified gaps are Low/Info severity with credible mitigations (either a defense-in-depth code path that IS tested, or device-level manual QA specs covering the same behavior). Gate passes in a single iteration; a second iteration is not warranted since no finding requires a code change before proceeding.

## Verdict

GATE: PASS
