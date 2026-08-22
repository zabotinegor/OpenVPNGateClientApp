# Android ADB Manual QA Runbook (OpenVPN Gate Client)

Reusable, non-secret setup/readiness/technique notes for real-device manual QA on this app.
Primary device used to date: Samsung Galaxy A71 SM-A715F, serial `<serial>` (adb over USB).

See [operations/device-qa-phone.md](../../../docs/operations/device-qa-phone.md) for this device's
general ADB workarounds (multi-user `SecurityException`, launcher-category launch, airplane-mode
network drop) — this file only adds what is specific to VPN connect/watchdog/auto-switch QA.

## Build, install, verify freshness

```
cd src
./gradlew.bat assembleDebugApp   # aggregate task, produces both mobile-debug.apk and tv-debug.apk
adb -s <serial> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <serial> shell dumpsys package com.yahorzabotsin.openvpnclientgate --user 0 | grep -E "versionName|versionCode|lastUpdateTime"
```

`assembleDebugApp` reporting `UP-TO-DATE` is not a cache trap here (unlike `testDebugUnitTestApp`,
see AGENTS.md PROCESS WARNING) — Gradle just means the existing APK's bytes already match the
current commit's inputs. Confirm freshness via the mobile APK file's own mtime (`ls -la
mobile/build/outputs/apk/debug/mobile-debug.apk`) and via `lastUpdateTime` after install, not
by requiring the build to say `BUILD SUCCESSFUL, N executed`.

## Multi-user shell gotcha

Same `SecurityException: ... user 150` issue as documented in
[operations/device-qa-phone.md](../../../docs/operations/device-qa-phone.md) — always pass
`--user 0` for `pm` package queries.

## Reliable non-GUI navigation

- Get exact tap coordinates with `adb shell uiautomator dump //sdcard/ui.xml` (note the **double
  leading slash** — a single `/sdcard/...` gets mangled by Git Bash path translation into
  `C:/Program Files/Git/sdcard/...` on Windows) then `adb pull //sdcard/ui.xml <local>` and grep
  `bounds="[...]"` for the target `resource-id`.
- The connect/disconnect control is a single reused element,
  `resource-id="com.yahorzabotsin.openvpnclientgate:id/start_connection_button"`, bounds
  `[53,2076][1027,2183]` on this device's 1080x2400 panel (center tap `540,2130`). It serves as
  both Start and Stop depending on state — one tap toggles regardless of current
  connect/disconnect state, which is convenient for scripted toggle-cycle soak testing.
- `adb shell input tap` coordinates are in **native device pixels**, not the scaled pixels shown
  in a `screencap` image if you view it resized. If you only have a resized screenshot, multiply
  its coordinates by (native_width / displayed_width) before tapping.
- Bring the app to the foreground from background using the launcher-category monkey trick from
  [operations/device-qa-phone.md](../../../docs/operations/device-qa-phone.md) (a plain
  `am start -n .../.SplashActivity` fails here the same way it does there). Specific to this QA
  area: doing so — importantly — fires `MainActivity.onStart()` → `VpnManager.syncStatus()` /
  `ACTION_SYNC_STATUS`, the same trigger a real user hits returning to the app.
- Background the app: `adb shell input keyevent KEYCODE_HOME`.

## Forcing a reconnect/auto-switch deterministically

VPN Gate community servers are live, unpredictable third-party endpoints — a "known-dead" server
picked in one QA session can be live again days/weeks later (observed: AU/Melbourne, single
server, previously reliably-dead, connected successfully in a later session). Do not hardcode a
specific country as "always dead". Two reliable techniques instead:

1. **Country selector server-count check**: open the server/country list in-app and read the live
   ping badges — a country still showing 0 live/green servers is currently dead and will
   reproduce the "5s no-reply → timed switch" behavior deterministically for that session only.
2. **Airplane-mode network drop** (reliable regardless of server liveness): the same
   `airplane-mode enable`/`disable` toggle documented in
   [operations/device-qa-phone.md](../../../docs/operations/device-qa-phone.md) (mind its settle-time
   note), applied while connected and held for ~8s. This reliably forces the engine to detect loss
   of network and drives the watchdog/auto-switch path, independent of any particular server's
   current availability. Combine with `KEYCODE_HOME` beforehand to exercise the
   backgrounded-reconnect scenario at the same time.

## Notification / foreground-service sanity

- Single active notification check: `adb shell cmd notification list | grep <package>` — expect
  exactly one line, same `id` across cycles. `adb shell dumpsys notification --noredact` shows a
  larger *history* buffer (multiple `NotificationRecord` entries with the same id from past
  updates) — that is normal and is not evidence of duplicate/stacked notifications; use `cmd
  notification list` for the current active set.
- Full-session crash-signature scan pattern used for foreground-service timing regressions:
  capture the whole session with `adb logcat -v time > session.log` (background process, `adb
  logcat -c` first to clear), then grep for
  `ForegroundServiceDidNotStartInTimeException|RemoteServiceException|FATAL EXCEPTION|AndroidRuntime.*FATAL|ANR in`.
  Also grep the app's own log tag(s) for its failure-path log line if the fix under test has one
  (e.g. `"Failed to enter controller foreground"` for the `OpenVpnService.enterControllerForeground()`
  guard-removal fix) — a single `E AndroidRuntime` false-positive risk is negligible since that
  string does not otherwise occur in normal operation.
- To prove a fix that touches `ACTION_START`/`ACTION_SYNC_STATUS` sequencing was actually
  exercised (not just "no crash by luck"), **do not rely on raw counts of `ACTION_START`,
  `ACTION_SYNC_STATUS`, and `Service destroyed` alone** — double-digit counts of all three do not
  prove the specific same-instance ordering fired, since unrelated fresh instances (full
  teardown → new instance) can produce the same counts without ever exercising it. A round-3 PR
  review found exactly this in `docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa.md`:
  16/17/27 counts, but the vulnerable ordering never actually occurred anywhere in the session (see
  that file's correction note). Instead, split the log into per-instance brackets by pairing each
  `Service created` with the next `Service destroyed` for the same PID, then check the order of
  actions **inside each bracket**: the vulnerable sequence is `ACTION_SYNC_STATUS` (creates or
  keeps the instance alive) followed by a *later* `ACTION_START` **within that same bracket, with
  no `Service destroyed` in between**. A soak run only demonstrates the fix was exercised if at
  least one bracket shows that exact order; brackets showing `ACTION_START` before
  `ACTION_SYNC_STATUS`, or a `Service destroyed` between the two, do not count as evidence of it.

## Reactive log-polling loops are too slow to hit sub-500ms race windows

A Bash loop that repeatedly does `tail -n N "$LOG" | grep -q "<trigger line>"` against a live
`adb logcat -v time > file` capture, then fires an `adb shell input tap` reaction, looks like it should
give ~10-50ms reaction latency (the `sleep` interval) but does not on this Windows/Git-Bash toolchain:
each loop iteration forks two subprocesses (`tail`, `grep`) plus the reaction's own `adb shell` call, and
that subprocess-spawn overhead was measured at 300-700ms+ per detected reaction in practice (confirmed
via wall-clock timestamps against the log's own timestamps), sometimes bad enough that a
300-400-iteration loop blew past this session's own Bash-tool command timeout before ever finding the
trigger line. This makes reactive polling unusable for windows narrower than roughly 500ms-1s (e.g.
`ServerAutoSwitcher`'s 350ms `retryCommitInFlight` retry-commit window,
`START_AFTER_STOP_DELAY_MS = 350` in `ServerAutoSwitcher.kt`).
**Workaround:** for windows that narrow, do NOT try to close the loop over logcat. Instead (a) run several
back-to-back real connection attempts to learn the session's own empirical event timing (e.g. "switch
decision fires ~2.4-2.5s after `ACTION_START`" against a `status_stall_timeout_seconds=1` server), then
(b) fire the reaction as a single blind `sleep <precomputed offset>` after the initiating tap, with a
handful of trials spanning a small offset sweep to cover jitter. Accept that live third-party server
response-time variability (VPN Gate community servers) means not every blind-timed trial will actually
land inside an open window — some will simply complete a normal connect with no window ever opening,
which is not a failure, just inapplicable data for that trial. For windows this narrow, on-device timing
should be treated as supporting/directional evidence alongside (not a replacement for) deterministic
unit-test coverage, which does not depend on wall-clock adb round-trips at all.
(Discovered during `fix/86cb35fbt-vpn-foreground-service-crash` manual QA round 3, 2026-08-16, trying
to force a genuine user-Disconnect tap inside the 350ms retry-commit window on real hardware.)

## Airplane-mode churn campaigns must reconnect before every cycle, not just the first

A backgrounded, fully-disconnected app does **not** auto-reconnect: no `ConnectivityManager` network
callback or similar exists to restart a connection when network is restored while backgrounded (see
`docs/features/connection-recovery.md` — `"completed full server cycle"` is a genuine terminal state
with no automatic follow-up). A multi-cycle airplane-mode churn campaign that blindly toggles
airplane mode on a fixed schedule will silently degrade into a series of no-ops after the auto-switcher
first reaches a terminal disconnected state (as few as 1 cycle in) — later cycles have nothing to
disrupt. **Fix:** reconnect (foreground + tap Connect + wait for `CONNECTED` + background again)
**before every single cycle**, not just the first. Verify a campaign actually churned by checking
`ACTION_START` timestamp density across the *whole* campaign window (`grep "ACTION_START" session.log`)
before trusting a "clean, no crash" result — a clean result from a campaign that only churned once is
much weaker evidence than the trial count suggests.
(Discovered during `fix/86cb35fbt-vpn-foreground-service-crash` manual QA round 5, 2026-08-16: a first
30-cycle attempt showed genuine churn only in cycle 1; a corrected 20-cycle campaign with per-cycle
reconnect produced 111 `ACTION_START`/107 switch-decisions/26 full-cycle-exhaustions.)

## This device can OS-kill the whole app process while backgrounded with an active FGS

Observed once on Samsung Galaxy A71 SM-A715F (Android 13, One UI): the entire app process — not just
the VPN session — was killed and restarted by the OS while merely backgrounded (`KEYCODE_HOME`), even
though it held an active foreground service. Signature: a fresh `CoreApp` onCreate log line with a new
PID, the new controller instance immediately reporting `LEVEL_NOTCONNECTED`/`NOPROCESS`, and
self-reaping via `ACTION_STOP_IF_IDLE` within ~1s. This is **not** a crash (no exception, no
`dumpsys dropbox` record) — it is this device's aggressive One UI background-process management, and
the controller's own idle-reap design handles it correctly. It does, however, starve an airplane-mode
churn campaign of a live session to disrupt on later cycles. **Mitigation for QA sessions:**
```
adb shell dumpsys deviceidle whitelist +com.yahorzabotsin.openvpnclientgate
adb shell dumpsys deviceidle disable
```
reduces (does not guarantee eliminating) this during a long campaign.

## `am start-service` cannot deliver ACTION_STOP to `.vpn.OpenVpnService` from adb shell

Attempted as a way to get deterministic (non-UI-tap-timed) delivery of an explicit user-disconnect
intent, to avoid racing a flickering connect/disconnect button label during an active internal retry
chain:
```
adb shell am start-service -n com.yahorzabotsin.openvpnclientgate/com.yahorzabotsin.openvpnclientgate.vpn.OpenVpnService -a stop --ez com.yahorzabotsin.openvpnclientgate.vpn.PRESERVE_RECONNECT false
```
Result: `Error: Requires permission not exported from uid 10813` — the service is correctly not
shell-invokable (it is not exported). Any future need for deterministic intent delivery to this service
for QA purposes requires an in-app debug-build-only hook (e.g. a guarded broadcast receiver), not a
shell workaround. The connect/disconnect toggle button's own UI label was also confirmed (via
`uiautomator dump` mid-retry) to flicker rapidly between Start/Stop during an active internal
auto-switch retry chain, making blind-coordinate `input tap` timing unreliable for distinguishing a
genuine user stop from the chain's own internal preserve-reconnect stop.
(Discovered during `fix/86cb35fbt-vpn-foreground-service-crash` manual QA round 5, 2026-08-16, trying
to force a genuine user-Disconnect tap during an active auto-switch retry on real hardware.)

## `am broadcast -a android.intent.action.AIRPLANE_MODE` is blocked from adb shell; use `cmd connectivity airplane-mode` instead

Attempted `adb shell settings put global airplane_mode_on 1` followed by `adb shell am broadcast -a
android.intent.action.AIRPLANE_MODE --ez state true` to toggle airplane mode for an auto-switch
regression trigger — the `settings put` succeeds (silently) but the broadcast fails with
`java.lang.SecurityException: Permission Denial: not allowed to send broadcast
android.intent.action.AIRPLANE_MODE from pid=<n>, uid=2000` (shell UID is not allowed to send that
system broadcast on this device's Android 13 build), so the actual network state never toggles despite
no visible error until you look at the command output. The already-documented-elsewhere reliable command
is `adb shell cmd connectivity airplane-mode enable` / `disable` (see
`docs/operations/device-qa-phone.md`) — it performs the toggle *and* fires the broadcast/settings update
together, with no separate `settings put` step needed. Use `cmd connectivity airplane-mode`, not the
`settings put` + `am broadcast` combination, for any future scripted airplane-mode toggle.
(Discovered during `fix/86cb35fbt-vpn-foreground-service-crash` manual QA round 6, 2026-08-17.)

## TV Wi-Fi ADB (`adb connect host:5555`) logcat capture drops intermittently under long sessions

A `adb -s <tv-ip>:5555 logcat -v time > file` capture left running for an extended real-device QA
session (release regression pass, several hours) was observed to silently die 2-3 times with exit
code 255 (task status `failed`), even though `adb devices` still showed the TV as `device` (connected)
immediately afterward -- this is Wi-Fi ADB connection churn on the TV box, not a real disconnect
requiring `adb connect` again in most cases (though `adb disconnect <ip>:5555 && adb connect
<ip>:5555` is a safe first fallback if a plain retry doesn't restart the pipe). **Fix:** just restart
the same `logcat -v time` command with `>>` (append) targeting the same log file -- `adb logcat`
replays from its own still-buffered ring on reconnect, so appending does not lose crash-detection
coverage across the gap, and the file's line count stays a valid intermediate freshness check (rules
out a silently-empty capture). Treat a killed logcat background task as routine on this TV device, not
a QA blocker -- just restart the pipe and continue. The phone (Samsung Galaxy A71, USB ADB) did not
show this pattern; it appears TV-Wi-Fi-specific.

## `wm density` tablet simulation: auto-rotate must be off for `user_rotation` to have any effect, even after wm density change

Continuing the tablet-simulation technique in this file's "Simulating a tablet" section (`docs/operations/device-qa-phone.md`):
when testing that a tablet-sized layout follows OS rotation-lock (as opposed to a phone's forced
portrait), setting `adb shell settings put system user_rotation 1` alone silently no-ops if
`accelerometer_rotation` (auto-rotate) is still `1` -- the OS just ignores the manual rotation request
while auto-rotate is on, and `mRotation` in `dumpsys window displays` stays unchanged with no error of
any kind. This produced a false "still locked to portrait" reading during `feature/release/21.08.2026`
QA (US-22 orientation lock) until `accelerometer_rotation` was confirmed and explicitly set to `0`
first, at which point `user_rotation` correctly drove `mRotation` to `ROTATION_90`/`ROTATION_0` in both
directions on the `wm density 240` (sw720dp) simulated tablet. Always verify
`adb shell settings get system accelerometer_rotation` reads `0` immediately before relying on
`user_rotation` for any orientation-lock test, phone or simulated-tablet alike.
(Discovered 2026-08-22, `feature/release/21.08.2026` release QA.)

## TV launcher category is `LEANBACK_LAUNCHER`, not `LAUNCHER`

`adb shell monkey -p <pkg> -c android.intent.category.LAUNCHER 1` (the standard mobile launch trick
documented elsewhere in this file and in `docs/operations/device-qa-phone.md`) returns `No activity
found` on the TV launcher and does nothing -- no crash, just silently fails to launch. Use
`android.intent.category.LEANBACK_LAUNCHER` instead for the `tv` module; this correctly resolves to
`.tv.SplashActivity` and launches normally. The `LAUNCHER`-category trick still applies unchanged to
the `mobile` module.
(Discovered 2026-08-22, `feature/release/21.08.2026` release QA, MIBOX4 TV device.)

## Simulating a pre-migration legacy SharedPreferences state without an old APK

To QA an upgrade/migration path (e.g. US-15/US-14-style enum-value or orphaned-key migrations) without
needing to build and install an actual pre-migration APK, write the target XML directly into the app's
`shared_prefs/` via `run-as` on a debug build. `adb shell run-as <pkg> sh -c 'cat > shared_prefs/x.xml'`
fails with a heredoc/quoting error (`sh: can't create temporary file ...: Permission denied`) when fed
a multi-line heredoc through nested `adb shell "..."` quoting -- the reliable path is: write the XML to
a local file, `adb push` it to a world-writable staging path (`MSYS_NO_PATHCONV=1 adb push local.xml
/data/local/tmp/x.xml` -- note the `MSYS_NO_PATHCONV=1` only on the command doing the remote-path
argument, not on any later `Read`/local-path-only command), then pipe it into the app's private storage
through `run-as`: `adb shell "cat /data/local/tmp/x.xml | run-as <pkg> sh -c 'cat > shared_prefs/x.xml'"`.
Force-stop (`am force-stop <pkg>`) before the next launch so the migration-on-read code path actually
runs against the freshly-written file rather than an already-loaded in-memory value.
(Discovered 2026-08-22, `feature/release/21.08.2026` release QA, US-15/86cavhuna upgrade-path
verification -- simulated a `server_source=LEGACY` + orphaned `custom_server_url` key install, force-
stopped, relaunched, confirmed silent fold to `DEFAULT_V2` with no crash and a non-empty server list.)

## Last validated
2026-08-22, against `feature/release/21.08.2026` HEAD `828dc1454b18af2ff0253b2714347951e693e758`.
