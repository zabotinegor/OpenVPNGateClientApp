# Docs Evidence — SUB-03 SSE Reconnect Correctness

**Flow:** `feature/sub-03-client-reconnect-correctness::SUB-03-SSE-RECONNECT`
**Date:** 2026-06-24
**GATE_RESULT:** PASS

## SDLC Gate Check

- `manualQa.status` = `passed` (2026-06-24T19:11:33Z)
- `defects` = `[]` (no open defects)
- Gate: PASSED — proceeding with docs update.

## Drift Audit Summary

### Files audited
- `src/docs/server-sync-flow.md` — stale (SSE trigger matrix and backoff description)
- `CLAUDE.md` — stale (key entry point description)
- `docs/runbooks/solutions.md` — missing two new knowledge-capture entries
- `docs/runbooks/how-to.md` — SSE verification section incomplete (onOpen sync, backoff guard)
- `AGENTS.md` — no update needed (no architecture or integration-point changes)

### Findings
| Severity | Finding |
|---|---|
| MAJOR | `server-sync-flow.md` trigger matrix listed SSE as events-only; backoff-reset description said "successful onOpen resets counter" (now wrong) |
| MAJOR | `CLAUDE.md` key entry point described SSE as events-only |
| MAJOR | `docs/runbooks/solutions.md` missing stale-data-on-reconnect and hot-reconnect-loop entries |
| MAJOR | `docs/runbooks/how-to.md` SSE verification missing onOpen sync and stability-threshold backoff-reset explanation |

## Files Updated

### `src/docs/server-sync-flow.md`
- Added `SSE connection open (onOpen)` row to the Trigger Matrix immediately above the `SSE server-changed push event` row. Both rows show `forceRefresh=true, cacheOnly=false`.
- Updated the Backoff bullet under the SSE Lifecycle section: removed "A successful `onOpen` resets the backoff counter"; replaced with correct description of `maybeResetBackoff()` stability-threshold guard (≥ 10 s) in `onClosed`/`onFailure`. Added explicit note that `onEvent` does NOT reset the counter (SUB-03 change).

### `CLAUDE.md`
- Updated `SseServerEventsClient.kt` key entry point description from "triggers server sync on `servers-changed` push event" to "triggers server sync on connection open (`onOpen`) and on `servers-changed` push events".

### `docs/runbooks/solutions.md`
- Added entry: "SSE reconnect shows stale server data: `onOpen` was a no-op — fixed in SUB-03"
  - Problem: onOpen was a no-op; sync only fired on events.
  - Solution: `syncCoordinator.sync(forceRefresh=true, cacheOnly=false)` added in `onOpen` coroutine.
- Added entry: "SSE hot-reconnect loop when degraded server sends events: `reconnectAttempt.set(0)` in `onEvent` bypassed backoff — fixed in SUB-03"
  - Problem: receiving any event reset the backoff counter, bypassing exponential backoff when a server sent events then immediately dropped the connection.
  - Solution: removed `reconnectAttempt.set(0)` from `onEvent`; stability-threshold check in `onClosed`/`onFailure` is now the sole backoff-reset path.

### `docs/runbooks/how-to.md`
- Updated "Verify SSE client connection on device" Step 2: added paragraph explaining that server-sync activity immediately after `connection opened` is expected (the new `onOpen` sync trigger from SUB-03).
- Updated Step 4: added note that `onOpen` sync and `servers-changed` sync are independent triggers; both call `syncCoordinator.sync(forceRefresh=true, cacheOnly=false)`.
- Added "Backoff reset — stability-threshold guard" subsection explaining `STABLE_CONNECTION_RESET_DELAY_MS` (10 s), that receiving events does NOT reset the counter, and that only `onClosed`/`onFailure` with ≥ 10 s elapsed resets it.

## Verification

All changed files cross-checked against `SseServerEventsClient.kt` source:
- `onOpen` at line 151: calls `syncCoordinator.sync(forceRefresh = true, cacheOnly = false)` in `clientScope?.launch { }` — confirmed.
- `onEvent` at line 165: no `reconnectAttempt.set(0)` present — confirmed.
- `maybeResetBackoff()` at line 143: checks `openedAt.get()` and `System.nanoTime() - t >= TimeUnit.MILLISECONDS.toNanos(stableConnectionResetDelayMs)` — confirmed. Called only from `onClosed` (line 181) and `onFailure` (line 195) — confirmed.
- `STABLE_CONNECTION_RESET_DELAY_MS = 10_000L` at line 257 — confirmed.
