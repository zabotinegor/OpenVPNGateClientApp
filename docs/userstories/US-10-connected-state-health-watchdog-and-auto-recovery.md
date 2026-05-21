# US-10 - Connected State Health Watchdog And Auto-Recovery

## Chat title

Story Spec - Fix intermittent state where VPN remains ON (key visible) but internet is unavailable until device reboot

## User story

As an OpenVPN client user,
I want the app to detect and recover from a broken data path while VPN is still reported as connected,
so that I do not stay in a false-connected state with no internet access.

## Start gate

Do not start implementation before reading `.sdlc/status.json` and verifying prior-step requirements for bug-intake routing.

## SDLC handoff metadata

- Flow status path: `.sdlc/status.json`
- Selected flow ID: `feature/us-10-connected-state-health-watchdog-and-auto-recovery::US-10`
- Active branch: `feature/us-10-connected-state-health-watchdog-and-auto-recovery`
- Required prior step for story phase: none (story is the first required step)
- Bug-intake routing note: no existing flow matched this defect class; missing prior flow metadata was handled by creating this new story flow from retained Manual QA evidence
- Story status update command:
  - `.github/scripts/update-sdlc-status.ps1 -FlowId "feature/us-10-connected-state-health-watchdog-and-auto-recovery::US-10" -Branch "feature/us-10-connected-state-health-watchdog-and-auto-recovery" -Step "story" -Status "ready" -StoryId "US-10" -StoryPath "docs/userstories/US-10-connected-state-health-watchdog-and-auto-recovery.md" -Evidence "docs/userstories/US-10-connected-state-health-watchdog-and-auto-recovery.md"`

## Background

### Bug-intake evidence summary

- Reporter behavior sequence (real device):
  - User paused connection, minimized app, reopened app, and repeated pause/exit/reopen flows.
  - VPN remained shown as ON after reopen steps.
  - By morning, internet traffic was unavailable while VPN key was still active.
  - Device reboot restored normal behavior.
- Retained artifacts:
  - `manual-qa/2026-05-20-bug-intake-pause-resume/QA-REPORT.md`
  - `manual-qa/2026-05-20-bug-intake-pause-resume/logcat-suite.txt`
  - `d:/Apps/OpenVPNClient/OpenVPNClientClientReports/logcat_20260520_102010.txt`

### Expected vs actual behavior

- Expected behavior:
  - If VPN is shown as connected, internet should be reachable through tunnel path.
  - If data path is broken, app should detect degradation and recover (reconnect or fail-safe transition), not remain indefinitely false-connected.
- Actual behavior:
  - In field reproduction history, VPN key remained active but internet became unavailable until full phone reboot.
  - Attached log window contains prolonged `LEVEL_CONNECTED` heartbeats without a corresponding recovery transition.

### Severity and impact

- Severity: High (user-impacting connectivity outage with misleading connected state)
- Impacted surfaces:
  - Android mobile foreground/background lifecycle
  - Pause/resume and reopen flows
  - Runtime connection-state presentation and recovery behavior

### Suspected affected area

- Core runtime service and state sync/recovery:
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ConnectionState.kt`
- Engine/app status boundary may not detect data-path health regressions when engine still reports connected.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | While app state is connected, runtime performs periodic health checks combining tunnel traffic progression and lightweight reachability probes. |
| AC-2 | If connected state persists but health checks fail for a configured threshold window, runtime marks connection as degraded and triggers controlled auto-recovery. |
| AC-3 | Auto-recovery must not require app restart or phone reboot and must attempt reconnect using existing safe reconnect path. |
| AC-4 | Recovery logic is debounced to avoid flapping (no reconnect storm during transient packet loss). |
| AC-5 | On successful recovery, state returns to connected and health watchdog counters reset cleanly. |
| AC-6 | If recovery fails repeatedly within a bounded retry window, app exposes a deterministic non-connected/failed state instead of indefinite false-connected presentation. |
| AC-7 | Structured logs include watchdog decisions and cause context (traffic delta, probe result, threshold reached, recovery attempt index) without leaking sensitive data. |
| AC-8 | Existing pause/resume UX contract remains intact (no regressions to pause/resume button state transitions). |
| AC-9 | Regression tests cover: healthy connected path, transient failure (no recovery), sustained failure (recovery triggered), and repeated failure fallback state. |

## Out of scope

- OpenVPN engine upstream protocol modifications in `src/external/OpenVPNEngine`
- Backend API/schema contract changes
- UI redesign of main screen controls unrelated to reliability signaling
- New user-facing settings for watchdog tuning in this story (defaults are sufficient)

## Risks and open questions

| ID | Risk or question | Current handling |
| --- | --- | --- |
| R-1 | Health probes may create false positives on unstable networks. | Use multi-signal threshold (traffic + probe), warm-up delay, and debounce before recovery. |
| R-2 | Aggressive retries could interrupt valid sessions. | Add bounded retry policy and cooldown interval. |
| R-3 | Vendor-specific Android networking behavior may differ by device/OEM. | Include OEM-focused manual QA scenario on MIUI real device and preserve detailed diagnostics. |
| R-4 | Reachability check target choice may be brittle. | Use existing trusted endpoint strategy already configured in app runtime; avoid hardcoded ad-hoc hosts. |
| R-5 | Missing historical flow metadata for this bug-intake could reduce traceability. | Active flow `feature/us-10-connected-state-health-watchdog-and-auto-recovery::US-10` records retained bug evidence and SDLC artifacts. |

## Implementation notes

- Keep implementation inside shared core module (`src/core`) and avoid launcher-specific logic.
- Recommended insertion points:
  - Extend connected-state polling path in `OpenVpnService.trafficPollRunnable`.
  - Add watchdog state model (consecutive-failure counter, last-healthy timestamp, cooldown marker).
  - Reuse existing reconnect dispatch path in `VpnManager`/`OpenVpnService` rather than creating parallel stop-start flow.
- Logging policy:
  - Use Timber/AppLog style already in project.
  - Keep logs concise and privacy-safe (no secrets, no raw private URLs beyond approved logging policy).
- Backward compatibility:
  - Do not change current pause/resume confirmation timeout semantics unless required by failing tests.

## Test scenarios

### Automated tests

| ID | Scenario |
| --- | --- |
| TS-1 | Connected with normal traffic progression keeps watchdog healthy and does not trigger reconnect. |
| TS-2 | Short temporary probe/traffic anomaly below threshold does not trigger recovery. |
| TS-3 | Sustained no-traffic + failed probe in connected state triggers one controlled recovery attempt. |
| TS-4 | Recovery success resets watchdog and returns stable connected state. |
| TS-5 | Repeated failed recoveries transition to deterministic non-connected/failure state and stop retry loop at configured limit. |
| TS-6 | Pause/resume flows continue passing existing acceptance coverage (`VPN-PAUSE-001..003`). |

### Manual QA focus

| ID | Scenario |
| --- | --- |
| MQ-1 | Reproduce original user sequence: pause, background, reopen, pause, close, reopen, switch server, reopen. |
| MQ-2 | Keep session long-running (soak window) and verify app self-recovers from simulated no-internet while key remains visible. |
| MQ-3 | Verify logs show watchdog threshold crossing and recovery decision with bounded retries. |
| MQ-4 | Confirm no reboot is required to restore connectivity after induced degraded state. |

## Definition of done

- AC-1 through AC-9 implemented and validated.
- Unit/integration tests for watchdog + recovery path are added and passing.
- Existing pause/resume regression coverage remains green.
- Manual QA confirms no indefinite false-connected state in targeted repro flow.
- SDLC evidence references retained bug-intake artifacts and new validation outputs.

## Code Implementator handoff seed

Use this story file as source of truth for implementation:
`docs/userstories/US-10-connected-state-health-watchdog-and-auto-recovery.md`