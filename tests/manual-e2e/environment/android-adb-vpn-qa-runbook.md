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

## Forcing a cold paging path: clear the v2 server-list cache via run-as

Warm-cache fast paths plus the periodic selected-country sync can silently mask scroll-triggered paging
tests: opening a country whose full list was persisted within TTL loads everything instantly through the
`warm-cache fast path` with zero page fetches. To QA lazy paging deterministically on a debug build,
force-stop first (so the store is not rewritten from memory), then delete the cache store and launch:
```
adb shell am force-stop com.yahorzabotsin.openvpnclientgate
adb shell run-as com.yahorzabotsin.openvpnclientgate rm shared_prefs/servers_v2_cache.xml
```
A cold open then logs `getServersPage[<CC>]: skip=0 take=<pageSize> ... hasMore=true`; a warm open logs
`getServersPage: warm-cache fast path country=... servers=<total>` instead.
(Discovered 2026-08-24, US-23 manual QA.)

## MIBOX4 drops DPAD key events at short injection intervals

Injecting `KEYCODE_DPAD_DOWN` faster than roughly one event per 200 ms on the MIBOX4 TV loses keys
silently — focus advances fewer rows than injected, so scroll-triggered paging looks stalled and row-count
assertions undercount. Use >=350-400 ms per event, or verify paging by the logcat fetch-line progression
(`getServersPage[<CC>]: skip=...`) instead of counting injected presses.
(Discovered 2026-08-24, US-23 manual QA.)

## Matching uiautomator dumps in non-ASCII locales (ru/pl)

Console output mangles Cyrillic text from UI dumps; grepping rendered strings from PowerShell produces
false negatives. Pull the dump file and read it with `Get-Content -Encoding UTF8`, match substrings
programmatically (`$xml.Contains('Корея')`) inside the script, and print only ASCII booleans/bounds to the
console. Related ClickUp gotcha: comment timestamps are UTC server-side — never derive an evidence
`SinceUtc` window from the device-local clock (device clocks in this environment are not UTC).
(Discovered 2026-08-24, US-23 manual QA.)

## MIUI device: `INSTALL_FAILED_USER_RESTRICTED` even with USB debugging authorized

A Xiaomi Mi 9T Pro (MIUI, Android 11) connected and authorized over USB ADB (`adb devices` showed
`device`, not `unauthorized`) still rejected every install attempt:
```
adb -s <serial> install -r mobile-debug.apk
# Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```
Escalating to the CLI fallback — pushing the APK and running `pm install` **on the device shell
itself** — failed identically:
```
adb -s <serial> push mobile-debug.apk /data/local/tmp/mobile-debug.apk
adb -s <serial> shell pm install -r /data/local/tmp/mobile-debug.apk
# Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```
The identical failure through both paths rules out an ADB-transport cause (this is not the
Wi-Fi-ADB flakiness documented elsewhere in this file) and confirms a device-level install
restriction. No confirmation dialog was pending on-screen; `dumpsys user` showed no restriction
flags on user 0; `settings get secure install_non_market_apps` was `1` (allowed). The most likely
cause on MIUI is the separate **"Install via USB"** developer-option toggle (independent of "USB
debugging") — MIUI gates silent installs on it even when the device is fully authorized for
debugging — or the device currently being in a MIUI "second space" that restricts installs to the
primary space. Both require a physical on-device settings change (Settings → Additional settings →
Developer options → **Install via USB**, and/or exiting the second space) — there is no ADB-only
workaround once this triggers.
(Discovered 2026-09-03 on a physical MIUI device during manual QA.)

## Local Android emulator as an airplane-mode-toggle fallback: works for ADB stability, but needs manual network setup and real host RAM headroom

When a physical device is unusable (Wi-Fi-ADB drops on airplane-mode toggle, or a MIUI install
restriction as above), a local AVD is a reasonable escalation: emulator ADB rides a local
loopback/pipe rather than the guest's own Wi-Fi radio, so `adb shell cmd connectivity
airplane-mode enable/disable` does not sever the ADB connection the way it can on a real Wi-Fi-ADB
device. However, two things are not automatic on a fresh Google-Play x86_64 AVD (tried:
`Medium_Phone_API_36.1`, API 36):
- **No network after boot** — `wlan0`/`eth0` both report `state DOWN` and `dumpsys connectivity`
  shows `Active default network: none`, even though `svc wifi enable` reports "Wi-Fi is enabled".
  Fix: explicitly connect to the AVD's built-in virtual AP —
  `adb shell cmd wifi connect-network AndroidWifi open` — after which `dumpsys wifi` shows
  `Supplicant state: COMPLETED` and an IP (`10.0.2.16` typically). Do this before trying any
  network-dependent flow (server-list fetch, VPN connect); an emulator that only just booted will
  otherwise fail every network call with `UnknownHostException`, which looks like a build/config
  problem but is not.
- **Host RAM headroom matters more than expected** — a boot attempted while the host had only
  ~2.7 GB free (of 12.5 GB total) produced a `systemui` ANR immediately on first app launch (needed
  a manual tap on the ANR dialog's "Wait" button to recover: `uiautomator dump` → find `bounds` for
  the "Wait" `text` node → `input tap` its center), 200% CPU / <60 MB free RAM inside the guest
  (`adb shell top`), and the emulator process (`qemu-system-x86_64.exe`) crashed outright shortly
  after (emulator log: `adb protocol fault (couldn't read status length)` followed by an unplanned
  snapshot-save/shutdown). None of this reproduces reliably — it is host memory starvation, not an
  app-under-test defect — so check `Get-CimInstance Win32_OperatingSystem |
  Select TotalVisibleMemorySize,FreePhysicalMemory` (PowerShell) before trusting an AVD-based QA
  session, and close other host applications first if free memory is only a few GB.
(Discovered 2026-09-03 on AVD `Medium_Phone_API_36.1` during manual QA.)

---

## Forcing a persisted-server-list "blank config" scenario — neutralize sync, don't race it

`SelectedCountryStore`'s persisted server list (`shared_prefs/vpn_selection_prefs.xml`, key
`selected_country_servers`) can hold a non-null entry with a blank `config` string — a real,
reachable data-shape condition (`ServerAutoSwitcher`'s blank-config fall-through) — but a naive
`run-as`-edit repro attempt gets defeated by two independent self-heal mechanisms:
1. **Live-process cache**: editing the SharedPreferences XML on disk via an external `run-as` shell
   while the app process is alive is invisible to that process — `SharedPreferencesImpl` serves reads
   from an in-memory map loaded at first access, not the file. The edit only takes effect on the next
   process start.
2. **Sync self-heal on restart**: even after force-stop + edit + relaunch, `ServersV2SyncCoordinator
   .syncSelectedCountryServers()` (splash preload and/or the first SSE `servers-changed` push, usually
   within a few seconds) re-fetches the country's server list — from network if reachable, else from
   the local `cache/v2_servers_<code>_<locale>.json` file — and overwrites the array via
   `saveSelectionPreservingIndex()`. `ServersV2Repository` explicitly filters/logs
   (`"Server X has empty configData — skipping"`) any blank-`configData` entry from either source, so
   a blanked entry never survives a completed sync, whether the sync went to the network or just hit
   the local cache.

Reliable technique (confirmed working, 2026-09-03): don't race the sync — make it permanently skip
the selected country instead, by corrupting its *identity*, not just the config payload:
1. Force-stop the app: `adb shell am force-stop com.yahorzabotsin.openvpnclientgate`.
2. Edit **both** `shared_prefs/vpn_selection_prefs.xml`'s `selected_country_servers` JSON array *and*
   the matching `cache/v2_servers_<code>_<locale>.json` file (also under `run-as`): blank the target
   server's `config`/`configData`, and set **every** server's `code`/`countryCode` field to a value
   not present in the live API (e.g. `"ZZ"`).
3. Also corrupt the top-level `<string name="selected_country">` value (e.g. append `"-QA"`).
   `syncSelectedCountryServers`'s country lookup is
   `countries.firstOrNull{it.code==selectedCountryCode} ?: countries.firstOrNull{it.name==selectedCountry}`
   — corrupting both the code and the name-fallback makes it permanently hit
   `"not in country list, skipping"`, so the blanked entry is never restored. No timing race needed
   after this; verify with `run-as cat shared_prefs/vpn_selection_prefs.xml`. Cosmetic side effect:
   UI shows a generic "?" flag and the corrupted country name — expected and harmless for the test.
4. Relaunch, connect to the (untouched, real) other server in the same country, then reproduce the
   drop via the airplane-mode/reconnect-timing technique in
   [operations/device-qa-phone.md](../../../docs/operations/device-qa-phone.md) ("Airplane-mode
   toggle for forcing a connection drop"). In practice, simply re-tapping Connect within ~1-2s of
   disabling airplane mode reliably lands on that doc's documented transient
   `LEVEL_NONETWORK`-right-after-reconnect window, which drives `ServerAutoSwitcher`'s
   `shouldSwitchImmediately` path straight into the blanked next-circular server before any tunnel is
   even established.
5. `adb shell pm clear com.yahorzabotsin.openvpnclientgate` afterward to remove the injected
   corruption and local cache files, then relaunch once to confirm a clean fresh state.

Applied successfully to verify the `ServerAutoSwitcher` blank-config fall-through (NOTCONNECTED-
confirmed path) on a physical device. Per-run logcat captures are kept with the story's QA artifacts,
not in this repository.
(Discovered 2026-09-03 during manual QA.)

## Backgrounding the app before a forced drop does not reproduce a rejected internal stop dispatch

Attempted as a way to force `ServerAutoSwitcher`'s stop-retry-timeout branch
(`dispatchStopAfterStopRetryTimeout()`) to actually fire, which requires the auto-switcher's internal
`ACTION_STOP` (sent via `Context.startService()` ahead of a switch retry) to be **rejected** on first
attempt. Hypothesis: Android's background-service-start restrictions might reject that dispatch if the
app UI is backgrounded at the moment it fires (plausible reading of the stale-re-dispatch fix's own
code comment, which describes the original defect as typically appearing "right after returning to the foreground,
which is also what lifts the background-start restriction that caused the original rejection").
Technique tried: connect normally, `KEYCODE_HOME` to background the app, then force the drop via
`cmd connectivity airplane-mode enable/disable`. Result: the internal `ACTION_STOP` dispatch was still
accepted immediately (`stopVPN invoked, result=true`, confirmed `NOTCONNECTED` ~135ms later) even while
backgrounded. Reason: `OpenVpnService` is already an **active foreground service** from the ongoing VPN
session at the moment this dispatch fires — that pre-existing foreground-service status exempts the
`startService()` call from the background-start restrictions that would apply to a cold start, so
merely backgrounding the *app UI* (as opposed to the service itself losing its foreground state) does
not reproduce the rejection. Combined with the earlier findings above (no reliable ADB-level
trigger for a rejected dispatch at all), this specific sub-path remains reserved for the existing
mutation-verified unit-test coverage rather than device QA.
(Discovered 2026-09-03 on a physical device during manual QA.)

## Selecting a server via the country/server picker mid-scenario self-heals injected blank-config corruption

Building on the "Forcing a persisted-server-list blank config" technique above: that technique
survives an app relaunch (the corrupted country identity prevents `syncSelectedCountryServers` from
matching and overwriting it), but it does **not** survive navigating the in-app country/server picker
UI (`server_selection_container` → country list → server list) to select a *different* server. Opening
that screen triggers a live fetch/re-render of the country's server list from network/cache, and
selecting a row from it writes a fresh, uncorrupted entry back into `SelectedCountryStore` — silently
undoing the injected blank `config`/`configData` and the `code`/`countryCode`/`selected_country`
corruption before any subsequent tap can exercise it. Confirmed directly: after the picker round-trip,
the UI showed the country name/flag reverted to the real value and the server count changed from the
corrupted list's size to the live list's size. **Implication for any scenario that needs to hold a
manually-selected blank-config server "current" while racing a separate in-flight auto-switch:** this
is not just a timing problem — the UI's picker and `ServerAutoSwitcher`'s own reconnect chain both
read/write the same `SelectedCountryStore` current-index field, so pre-positioning the UI on a
specific (blank) server and then triggering an unrelated real server's auto-switch chain cannot be
done independently; whichever acts first can overwrite the other's selection. Do not rely on the
picker to hold a corrupted selection past its own navigation.
(Discovered 2026-09-04 on a physical device during manual QA.)

## `am start-service` non-exported rejection applies identically to `ACTION_START`, not just `ACTION_STOP`

The existing "`am start-service` cannot deliver ACTION_STOP..." entry above documents the rejection
for `ACTION_STOP`; re-verified this round that the identical `Error: Requires permission not exported
from uid <n>` rejection applies to `ACTION_START` on the same service (same manifest, same
non-exported status — the action extra does not change the permission check). There is no
debug-build-only broadcast/intent hook in `OpenVpnService` for QA-only intent injection. Any QA
scenario brief that assumes "ADB `ACTION_START` intent injection" as a fallback technique for this
service should be corrected — it is not viable on this app; the only way to deliver a real
`ACTION_START` with an attacker/tester-chosen config value is through the app's own UI (or an
in-app debug hook, not present today).
(Discovered 2026-09-04 on a physical device during manual QA.)

## Last validated
2026-09-04, on a physical Android device.
