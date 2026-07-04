# Docs Review — US-12: Hardprobe on Every VPN Disconnect
**Iteration:** 2
**Branch:** `feature/us-12-hardprobe-on-disconnect`
**Date:** 2026-06-20
**Reviewer:** Claude Code

---

## Files Reviewed

| File | Status |
|---|---|
| `src/docs/server-sync-flow.md` | PASS |
| `docs/runbooks/how-to.md` | PASS |
| `docs/qa-evidence/us-12-review-iter2.md` | PASS — exists and non-empty |
| `docs/qa-evidence/us-12-gate-iter2.md` | PASS — exists and non-empty |

---

## `src/docs/server-sync-flow.md` — Hardprobe Trigger Points

Section present at lines 87–103. All 5 trigger points are listed:

1. Autoswitch timeout / immediate switch (`ServerAutoSwitcher.requestSwitchNow()`) — SUB-04
2. Watchdog recovery (`OpenVpnService.handleConnectedProbeResult()`) — SUB-04
3. **User-initiated disconnect** (`OpenVpnService.finishStopFlowConfirmed()`) — US-12
4. **DEFAULT_V2 hydration early-return** (`ServerAutoSwitcher.requestSwitchNow()`) — US-12
5. VPN_STATUS engine auto-switch (`OpenVpnService.updateState()`) — SUB-04

Both US-12 additions are present and accurately described. `serverId == 0` suppression guard documented at end of section. WorkManager KEEP deduplication noted. No updates required.

---

## `docs/runbooks/how-to.md` — Hardprobe Enqueue During VPN Lifecycle

Section present at lines 102–151.

**Table check:** All 5 events present including:
- "DEFAULT_V2 hydration early-return" row — present, notes "Added in US-12"
- "User-initiated disconnect" row — present, notes "Added in US-12"

**`serverId == 0` guard explanation:** States "The currently selected server IP does not match the last-started IP" — correctly reflects IP-based matching (not config-based). Consistent with the merged dev implementation of `getCurrentServerIdIfMatchingLastStarted`.

**References:** All file paths accurate; references `finishStopFlowConfirmed`, `handleConnectedProbeResult`, `updateState` in `OpenVpnService.kt` and `requestSwitchNow` in `ServerAutoSwitcher.kt`. Also references `US-12` user story doc and SUB-04 doc.

No updates required.

---

## Changes Made

None. Both documents were already accurate and complete for US-12.

---

## Result

GATE_RESULT: PASS
BLOCKING_COUNT: 0
