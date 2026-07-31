# VPN Pause/Resume/Stop Flow

## Scope

This document describes the main-screen connection-control state machine: how Pause, Resume, and
Stop transitions must behave, and the invariants that must hold across them. This applies to both
mobile and TV surfaces (both drive the same underlying state).

## State Model

**This table describes UI phases, not the state enum.** The underlying machine is
`ConnectionState` with six values — `DISCONNECTED, CONNECTING, CONNECTED, PAUSING, PAUSED,
DISCONNECTING` — documented in [vpn-connection.md](vpn-connection.md). There is no `RESUMING` state;
"Resuming" below is a UI phase that maps onto `CONNECTING`.

| UI phase | `ConnectionState` | Visible controls | Notes |
| --- | --- | --- | --- |
| Disconnected | `DISCONNECTED` | Start connection | `pause_connection_button` is absent entirely. |
| Connected | `CONNECTED` | Pause + Stop | `start_connection_button` doubles as the Stop action in all active states (text changes, same view id). |
| Pausing | `PAUSING` | Stop only | Transient: `pause_connection_button` is hidden immediately on tap, before `Paused` is reached — status text/logs show a pausing indicator. Can be short enough on fast devices/TV that broad polling misses the frame (see QA note below). |
| Paused | `PAUSED` | Resume + Stop | `pause_connection_button` shows Resume; `start_connection_button` remains the Stop action. |
| Resuming | **`CONNECTING`** | Stop only | Transient: `pause_connection_button` (Resume) is hidden until connected; `start_connection_button` shows the same connecting-progress sequence as a fresh `disconnected → connected` connect (TCP/connect/auth/config stages). `resumeTransitionInFlight` suppresses a stale `PAUSED` arriving mid-resume. |
| Stopping | `DISCONNECTING` | Stop only | Transient, entered from any active phase on Stop. Held sticky while the engine reports a teardown detail (`NOPROCESS`, `EXITING`, `DISCONNECTED`) so the UI does not flicker back through an intermediate phase. |

Control ids are stable across all active states — only the label/action on `start_connection_button`
and the visibility of `pause_connection_button` change.

## Invariants That Must Hold

1. **Immediate visual exit from Connected on Pause tap.** The UI must not remain visually stuck on
   `Connected` after Pause is tapped — an intermediate `Pausing` indication must be observable
   (status text, logs, or focused polling), even if the frame is brief.
2. **Resume renders the same progress sequence as a fresh connect.** `paused → connected` must show
   connecting-progress statuses on `start_connection_button` identical in kind to
   `disconnected → connected` — Resume is not a different, abbreviated code path from the user's
   point of view.
3. **No bounce-back to Paused once reconnect starts.** After Resume is tapped, `pause_connection_button`
   must not reappear (i.e. the UI must not flash back into the Paused view) before the connected
   state is reached. This was historically the most fragile invariant — see the QA note on
   transient-frame polling below before treating an apparent bounce as a real regression.
4. **Stop works from both Connected and Paused.** Tapping Stop (`start_connection_button` in its
   active-state role) from either state disconnects and returns to Disconnected, hiding Pause/Resume
   and Stop, leaving only Start connection visible.

## Source of Truth

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/VpnManager.kt` —
  `pauseVpn(context)` (line 53) and `resumeVpn(context)` (line 83) are the entry points that drive
  the transitions above.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ConnectionState.kt` — the state
  enum/model consumed by the UI layer to render the controls above.

## Regression Coverage

- `OpenVpnServicePauseLifecycleTest`, `OpenVpnServicePauseTimeoutTest`,
  `VpnManagerPauseRaceConditionsTest`, `ConnectionStateManagerTest`
  (`src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/vpn/`) are the authoritative
  automated coverage for this flow. The manual QA story that originally validated it
  (`VPN-PAUSE-RESUME-FLOW`, cases `VPN-PAUSE-001/002/003`) has been retired now that this
  automated coverage exists; use these tests as the reference for expected behavior instead of
  re-deriving it from first principles.

## QA Gotchas Worth Keeping

- **Transient frames can be missed by broad/full-suite polling**, especially on Android TV, where
  `Pausing` and the reconnect-progress frame during Resume can be short enough that a full-suite
  polling loop samples past them. If a full run shows the flow succeeding overall but doesn't show
  evidence of the `Pausing` or reconnect-progress frame specifically, re-run with focused/tight
  polling around just that transition before treating it as a regression — this is a known
  measurement-granularity issue, not necessarily a product defect.
- **MIUI's `uiautomator dump` can print `theme_compatibility.xml` errors to stderr** even when the
  XML dump itself is generated correctly — don't treat that stderr noise alone as a failed dump.
- Manual re-validation automation, if needed: `tests/manual-e2e/automation/run-mobile-pause-button-qa.ps1`
  and `tests/manual-e2e/automation/run-tv-pause-resume-e2e.ps1` (both remain in the repo; they
  handle launch/setup, pause/resume/stop checks, screenshots, and report generation).

## Related Documents

- `src/docs/INDEX.md` — knowledge-base catalog
- `src/docs/server-sync-flow.md` — server-list sync and hardprobe trigger points, which interact
  with connection state via `ServerAutoSwitcher`/`OpenVpnService`
- `CLAUDE.md` — architecture overview and entry points

---

*Last verified against: `VpnManager.kt`/`ConnectionState.kt` + the 4 automated pause/resume tests (2026-07-25).*
