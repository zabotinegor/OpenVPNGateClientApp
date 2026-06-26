# Review Comments — Round 2 Evidence
## PR #110 · fix/fgs-crash-rapid-reconnect → dev

Date: 2026-06-25

---

## Thread 3 — Gemini HIGH (PRRT_kwDOONeEXM6MWp5p)

**File:** `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt:1723`

**Verdict: REJECTED**

**Evidence:**

1. **Stale state at call time.** `ConnectionStateManager.updateFromEngine()` is called at line 1737, *after* the foreground guard evaluated at lines 1721-1726. At the point the guard runs, `ConnectionState` reflects the *previous* engine level. Example: on the first `LEVEL_CONNECTED` callback, `ConnectionState` is still `CONNECTING`, so `isDisconnected` would be `false` and `exitControllerForeground()` would be skipped incorrectly.

2. **`oneShotSyncRequested` does not exist in `syncEngineState` scope.** Searching the entire `OpenVpnService.kt` file confirms there is no field or local variable by this name accessible in `syncEngineState`.

3. **`ConnectionStateManager.state`** is the public name for the state flow in the Gemini suggestion; the actual field is `ConnectionStateManager.reconnectingHint` (which does exist). The suggestion references a field that does not match the codebase API.

4. **The underlying concern was already fixed.** The stuck-FGS problem on failed start was addressed by the `reconnectingHint` guard (lines 1719-1726, current code). When no chained auto-switch is pending, `reconnectingHint=false` → `reconnectPending=false` → `exitControllerForeground()` runs on idle levels.

5. **Round 1 duplicate.** This is a re-submission of the same suggestion that was already rejected in round 1 (PRRT_kwDOONeEXM6MWRm5, now resolved). No new evidence was added.

**Code state (lines 1719-1726):**
```kotlin
val idleLevel = level == ConnectionStatus.LEVEL_NOTCONNECTED || level == ConnectionStatus.LEVEL_NONETWORK
val reconnectPending = idleLevel && try { ConnectionStateManager.reconnectingHint.value } catch (_: Exception) { false }
if (controllerForegroundActive
    && level != ConnectionStatus.LEVEL_START
    && level != ConnectionStatus.UNKNOWN_LEVEL
    && !reconnectPending) {
    exitControllerForeground()
}
```

---

## Thread 4 — Codex P2 (PRRT_kwDOONeEXM6MWrjP)

**File:** `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt:1720`

**Verdict: REJECTED**

**Evidence:**

The concern was: "a failed start that never reached LEVEL_CONNECTED would leave FGS notification alive because reconnectingHint exemption blocks exitControllerForeground()."

Verified by tracing the `reconnectingHint` lifecycle:

1. **Default value:** `_reconnectingHint = MutableStateFlow(false)` (ConnectionState.kt:65). On a fresh `ACTION_START` that has never triggered an auto-switch chain, `reconnectingHint=false`.

2. **Only set to `true` in two places:**
   - `ServerAutoSwitcher.beginChainedSwitch()` (line 137) — only called when the auto-switcher decides to chain to the next server.
   - `ServerAutoSwitcher.requestSwitchNow()` — same path.
   Both are called *before* `VpnManager.stopVpn()`, so the hint is always set before the engine reaches `LEVEL_NOTCONNECTED`.

3. **Failed start path:** Engine is started → engine fails → AIDL delivers `LEVEL_NOTCONNECTED`. `ServerAutoSwitcher.onEngineLevel()` is called with `LEVEL_NOTCONNECTED` on line 1732. Since no timer is active and `waitingStopForRetry=false`, the auto-switcher calls `cancel(resetCycle=true)` (line 124), which does **not** call `setReconnectingHint(true)`. So `reconnectingHint=false` throughout.

4. **Guard evaluation:** `idleLevel=true`, `reconnectingHint.value=false` → `reconnectPending=false` → the `!reconnectPending` condition is `true` → `exitControllerForeground()` IS called. The failed-start FGS notification is removed correctly.

5. **Round 1 duplicate.** This is a re-submission of the Codex concern from round 1 (PRRT_kwDOONeEXM6MWTAB, now resolved). The fix was already applied and verified.

**Conclusion:** `reconnectingHint` correctly handles the failed-start AIDL `LEVEL_NOTCONNECTED` path. No code change required.

---

## Threads 5, 6, 7 — Gemini MEDIUM (PRRT_kwDOONeEXM6MWp52), Codex P2 (PRRT_kwDOONeEXM6MWrjU), Gemini MEDIUM (PRRT_kwDOONeEXM6MWp58)

**File:** `.github/skills/e2e-manual-testing/references/testing-knowledge-index.md:25` and `:40`

**Verdict: ACCEPTED**

**Evidence:**

AGENTS.md rule (lines 11-14):
> `AGENTS.local.md` at the repo root is the single place for **machine-specific paths and local-only context** that agents need but that must never be committed.

The file contained:
- Line 25: `- Service repo: d:\Apps\OpenVPNClient\OpenVPNClientClientApp`
- Line 40: `- Service repo: d:\Apps\OpenVPNClient\OpenVPNClientClientApp`

Both are Windows-only absolute paths that are machine-specific and violate the AGENTS.md rule.

**Fix applied:** Both lines replaced with `- Service repo: zabotinegor/OpenVPNGateClientApp` (GitHub repository identifier — machine-independent and sufficient for agents to locate the repo).

**Commit:** see git log for this branch after this round.

---

## Summary

| Thread | Verdict | Change |
|--------|---------|--------|
| T3 Gemini HIGH | REJECT | None |
| T4 Codex P2 | REJECT | None |
| T5 Gemini MEDIUM | ACCEPT | Doc fix (line 25) |
| T6 Codex P2 | ACCEPT | Same doc fix (line 25) |
| T7 Gemini MEDIUM | ACCEPT | Doc fix (line 40) |
