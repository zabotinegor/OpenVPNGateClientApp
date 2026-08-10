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

## Last validated
2026-08-10, against `fix/86cb35fbt-vpn-foreground-service-crash` HEAD `9032cf9`.
