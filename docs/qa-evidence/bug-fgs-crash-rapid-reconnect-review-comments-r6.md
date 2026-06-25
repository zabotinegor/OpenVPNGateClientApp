# Review Comments Evidence — Round 6
**PR:** https://github.com/zabotinegor/OpenVPNGateClientApp/pull/110  
**Branch:** `fix/fgs-crash-rapid-reconnect`  
**Date:** 2026-06-26  
**Commit:** `972e627`

## Threads Processed

### Thread 1 — PRRT_kwDOONeEXM6MYCVc (Codex P2) — ACCEPTED / FIXED
**File:** `src/core/.../vpn/OpenVpnService.kt`, line 1728  
**Claim:** `userInitiatedStart` is never cleared in `syncEngineState()` when `LEVEL_CONNECTED` arrives via the AIDL path. Only `updateState()` (VPN_STATUS path, line 1114) cleared it. After a successful connect, if the server drops the connection (non-user-initiated), the FGS guard evaluates `idleLevel=true && userInitiatedStart=true → reconnectPending=true` → `exitControllerForeground()` NOT called → FGS stuck showing "VPN connecting" indefinitely.

**Fix applied:**
- Added `if (level == ConnectionStatus.LEVEL_CONNECTED) { userInitiatedStart = false }` after the FGS guard block in `syncEngineState()` (line 1735–1743 in final form) with a detailed comment explaining the AIDL vs VPN_STATUS path split.
- Added unit test `syncEngineState_clearsUserInitiatedStart_onLevelConnected` in `OpenVpnServiceNotificationTest.kt`. The test sets `userInitiatedStart=true` via reflection, invokes `syncEngineState()` directly via reflection (bypassing `suppressEngineState` which only affects the VPN_STATUS path), and asserts `userInitiatedStart` is `false` after `LEVEL_CONNECTED`. **Test PASSED** (10/10 pass in `OpenVpnServiceNotificationTest`).

---

### Thread 2 — PRRT_kwDOONeEXM6MYCeY (Gemini HIGH) — REJECTED
**File:** `OpenVpnService.kt`, line 123  
**Claim:** `userInitiatedStop` should use synchronized lock + ConnectionStateManager guard.

**Rejection rationale:**
1. The synchronized-lock suggestion (preventing race between `syncEngineState()` on the binder thread and `onDestroy()` on the main thread) is a pre-existing architectural pattern predating this PR. The current binder-thread FGS call is not a regression introduced here. A full synchronized-lock refactor is out of scope for this targeted fix.
2. The ConnectionStateManager guard suggestion (use `ConnectionStateManager.state.value == DISCONNECTED` instead of the `reconnectingHint`/`userInitiatedStart` guard) is identical to the suggestion rejected 4 times in rounds 1-5 (threads PRRT_kwDOONeEXM6MWRm5, PRRT_kwDOONeEXM6MWp5p, PRRT_kwDOONeEXM6MXcYv, PRRT_kwDOONeEXM6MXvE0). The concrete blockers remain: `ConnectionStateManager.updateFromEngine()` runs AFTER the guard evaluates (state is stale at evaluation time); and the state machine cannot express "idle engine level but reconnect in-flight" without the explicit hint flags.

---

### Thread 3 — PRRT_kwDOONeEXM6MYCee (Gemini MEDIUM) — REJECTED
**File:** `src/docs/server-sync-flow.md`, line 131  
**Claim:** Doc should describe ConnectionStateManager internal state machine instead of engine-layer status checks.

**Rejection rationale:**
- The doc accurately reflects the actual implementation: the FGS guard uses engine-level idle detection (`LEVEL_NOTCONNECTED`/`LEVEL_NONETWORK`) conditioned on `reconnectingHint` and `userInitiatedStart`. Rewriting it to describe a ConnectionStateManager approach would make the doc factually incorrect.
- This suggestion was rejected in rounds 1-5 (same threads as above). The doc was updated in round 3 (commit `29ad773`) to explicitly document the conditional guard rationale and the reason ConnectionStateManager state is not used.

---

### Thread 4 — PRRT_kwDOONeEXM6MYCei (Gemini MEDIUM) — ACCEPTED / FIXED
**File:** `tests/manual-e2e/.../cases/MQ-BUG-RRC-001-rapid-reconnect-no-crash.md`, line 9  
**Claim:** ADB commands hardcode device serial `R58N849XQEY`.

**Fix applied:**
- Replaced all occurrences of `R58N849XQEY` with `<your-device-serial>` in all 4 test case files:
  - `MQ-BUG-RRC-001-rapid-reconnect-no-crash.md` (Setup step 2, Result line, Evidence section)
  - `MQ-BUG-RRC-002-normal-vpn-connect.md` (Setup step 2, Result line)
  - `MQ-BUG-RRC-003-disconnect-reconnect-stability.md` (Setup step 2, Result line)
  - `MQ-BUG-RRC-004-probe-enqueued-on-disconnect.md` (Setup step 2, Result line)

---

## Commit SHA
`972e627`

## Verification Notes
- Unit test `syncEngineState_clearsUserInitiatedStart_onLevelConnected` PASSED.
- All 10 tests in `OpenVpnServiceNotificationTest` PASSED (10/10, 0 failed).
- The fix is minimal and surgical: only adds the `userInitiatedStart = false` clear in `syncEngineState()` and does not touch the FGS guard logic.
- The `userInitiatedStart` clear in `updateState()` (line 1114) is unaffected and remains correct for the VPN_STATUS path.
- Hardcoded serial removal is cosmetic/portability-only; no logic changes in test cases.
