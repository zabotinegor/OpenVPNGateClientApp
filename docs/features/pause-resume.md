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
| Pausing | `PAUSING` | Pause (unchanged label) + Stop | Transient: `pause_connection_button` stays visible showing "Pause" (not yet toggled to "Resume") until `Paused` is confirmed — status text shows a pausing indicator. Was previously hidden for this whole window, which collapsed the row and shifted the layout below it whenever the window lasted long enough to be visible (see Historical Bug note below); fixed by keeping it in the same visible branch as `CONNECTED`. |
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
5. **`pause_connection_button` must never disappear or collapse during `PAUSING`.** The row it
   occupies must stay put for the whole `PAUSING` window — do not gate its visibility on anything
   other than "is the pause/resume control relevant right now" (i.e. it must cover `CONNECTED`,
   `PAUSING`, and `PAUSED` as one group). `PAUSING` starts synchronously the instant Pause is
   tapped, before the engine confirms anything, and its duration is not bounded tightly — treating
   it as a hide-worthy transient state (matching `DISCONNECTED`/`CONNECTING`/`DISCONNECTING`
   instead of `CONNECTED`/`PAUSED`) collapses this row and visibly shifts the server-selector and
   Stop button below it. See Historical Bug below.

## Source of Truth

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/VpnManager.kt` —
  `pauseVpn(context)` and `resumeVpn(context)` are the entry points that drive
  the transitions above.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ConnectionState.kt` — the state
  enum/model consumed by the UI layer to render the controls above.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsPresenter.kt`
  — `buildPauseButtonModel(state)` is what decides `pause_connection_button` visibility/label;
  `buildButtonModel(state, ...)` does the same for `start_connection_button`'s Stop/Start role. Any
  new `ConnectionState` branch added to one should be checked against the other for consistency.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt` —
  `pauseActionInFlight`/`PAUSE_TRANSIENT_CONNECTING_LEVELS` guard against a stale connecting-family
  AIDL/VpnStatus status arriving mid-pause; `PAUSE_CONFIRMATION_TIMEOUT_MS` (10s) and
  `PAUSE_RETRY_AT_MS` (5s, one resend) bound how long the app waits for the engine's `PAUSED`
  confirmation before reconciling back to whatever the last observed engine level was. The engine's
  pause is a `SIGUSR1`-triggered disconnect that stops at a management `HOLD` checkpoint before
  reconnecting (`OpenVpnManagementThread.handleHold` in the vendored engine); a slow teardown before
  reaching that checkpoint (explicit-exit-notify retries, TLS session close) is what drives the
  need for a 10s rather than a snappier client-side timeout.

## Regression Coverage

- `OpenVpnServicePauseLifecycleTest`, `OpenVpnServicePauseTimeoutTest`,
  `VpnManagerPauseRaceConditionsTest`, `ConnectionStateManagerTest`
  (`src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/vpn/`) and
  `ConnectionControlsPresenterTest` (button visibility/label per state, in
  `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/`) are the
  authoritative automated coverage for this flow. The manual QA story that originally validated it
  (`VPN-PAUSE-RESUME-FLOW`, cases `VPN-PAUSE-001/002/003`) has been retired now that this
  automated coverage exists; use these tests as the reference for expected behavior instead of
  re-deriving it from first principles.
- Gap: no automated coverage asserts the guard set (`PAUSE_TRANSIENT_CONNECTING_LEVELS`) stays
  narrow — nothing would fail today if a future edit added a genuine failure level
  (`LEVEL_AUTH_FAILED`, `LEVEL_NONETWORK`) to it, which would silently swallow that failure during a
  pause with no recovery path. Nor does anything assert the `ServerAutoSwitcher` half of the guard.
  Worth closing before touching either guard again.

## Historical Bug: the pause "screen flicker"

`pause_connection_button` used to be hidden (`visible=false`) for any `ConnectionState` other than
`CONNECTED`/`PAUSED`, including `PAUSING` — the same catch-all branch as the genuinely inactive
states. Since `PAUSING` starts synchronously on tap and its duration isn't tightly bounded, whenever
it lasted long enough to render at all, the row collapsed and the server-selector/Stop button below
it visibly jumped up and back once `PAUSED` arrived and the button reappeared. `buildButtonModel()`
(the Stop/Start button) never had this problem — it already mapped `PAUSING` to the same visible
"Stop Connection" as `CONNECTED`.

Root-caused via frame-by-frame analysis of a 66fps slow-motion screen recording: the button was
absent for ~17 frames (~255ms) before reappearing. A separate, real race was found and fixed first
(a stale connecting-family AIDL/VpnStatus status arriving mid-pause could briefly force
`ConnectionState` through `CONNECTING`) but turned out not to be the cause of the visible
flicker — logs confirmed the state machine was already going `PAUSING → PAUSED` directly with no
`CONNECTING` in between while the layout still visibly jumped. Two separate defects, both worth
having fixed, only one was the reported symptom.

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
- **Filming a mirrored screen (e.g. scrcpy on a monitor) with a phone camera produces false
  positives.** A handheld camera's rolling shutter can catch the monitor mid-refresh at the exact
  moment on-screen content legitimately changes, producing a single doubled/color-fringed frame
  that looks like an app-level flash but isn't one. A direct screen recording (the device's own
  recorder) or a slow-motion capture of the device's own screen are both immune to this and should
  be preferred over a camera-of-a-monitor recording when investigating a reported visual glitch.
- `ConnectionControlsPresenterTest`'s default `@Config(manifest = Config.NONE)` cannot resolve real
  string resources — any assertion calling `context.getString(R.string.*)` inside a presenter
  method throws `Resources.NotFoundException`, silently limiting most of that file's existing tests
  to comparing `R.id` ints rather than resolved text. Per-test `@Config(manifest =
  "src/main/AndroidManifest.xml", sdk = [27], packageName =
  "com.yahorzabotsin.openvpnclientgate.core")` fixes it for that one test method (see
  `CountryListAdapterTest`/`SpeedometerViewTest` for the same pattern already in use elsewhere).

## Related Documents

- `docs/INDEX.md` — knowledge-base catalog
- `docs/features/server-sync.md` — server-list sync and hardprobe trigger points, which interact
  with connection state via `ServerAutoSwitcher`/`OpenVpnService`
- `CLAUDE.md` — architecture overview and entry points

---

*Last verified against: `VpnManager.kt`/`ConnectionState.kt`/`ConnectionControlsPresenter.kt`/`OpenVpnService.kt` + the pause/resume automated test suite (2026-09-14).*
