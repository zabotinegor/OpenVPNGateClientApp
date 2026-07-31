# Android QA Runbook

This is a chronological per-story QA log (distinct from `docs/guides/adb-cookbook.md`,
which is topic-organized reusable snippets — don't duplicate content into both).



## Index

Read this list first and jump to the one relevant heading — do not read the whole file.

- [SUB-01 — TS-8 Deferred Manual QA](#sub-01--ts-8-deferred-manual-qa)
- [SUB-02 — WorkManager Probe Request Queue Manual QA](#sub-02--workmanager-probe-request-queue-manual-qa)
- [MP-20260621 SUB-02 — Android SSE Client for Server-Push Notifications](#mp-20260621-sub-02--android-sse-client-for-server-push-notifications)
- [Logcat commands for SUB-01 regression validation](#logcat-commands-for-sub-01-regression-validation)
- [SUB-05 — Instrumented test fixes](#sub-05--instrumented-test-fixes)
- [Per-App Locale Override (Samsung/One UI Workaround — SUB-07)](#per-app-locale-override-samsungone-ui-workaround--sub-07)
- [Simulating a TV D-pad Long-Press via ADB (MIBOX4 / Android 9, API 28)](#simulating-a-tv-d-pad-long-press-via-adb-mibox4--android-9-api-28)
- [`adb install -r` failing silently on a network-connected (Wi-Fi ADB) TV target — Windows/Git Bash](#adb-install--r-failing-silently-on-a-network-connected-wi-fi-adb-tv-target--windowsgit-bash)

---

## SUB-01 — TS-8 Deferred Manual QA

**Story:** the ClickUp story

TS-8 is the end-to-end ping display verification. It is blocked on the server team shipping `id` and
`ping` fields in the `DEFAULT_V2` API response (`VpnServerV2ListItemDto`). Until then:

- The server list will display `0 мс` for all V2 servers (expected behavior — same as before SUB-01)
- The `ServerV2.id` field will default to `0` for all servers

### When the server team ships `id` and `ping` (tracked in server repo `US-12-server-list-expose-id.md`)

1. Connect device: `adb devices` — ensure a real device is listed
2. Clear app data (optional, to avoid cache): `adb shell pm clear com.yahorzabotsin.openvpnclientgate`
3. Launch app: `adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
4. Select a V2-served country and wait for server list to load
5. Verify: at least one server shows non-zero ping (e.g., `37 мс`)
6. Logcat check: `adb logcat -d | grep -E "(fetchAllPages|ServersV2|JsonSyntax)"` — must show no `JsonSyntaxException`

### Devices tested for SUB-01 regression (AC-5, AC-7 cache backward-compat)

- Samsung Galaxy A71 SM_A715F, Android 13, ADB serial <your-device-serial>

---

## SUB-02 — WorkManager Probe Request Queue Manual QA

**Story:** the ClickUp story
**Device tested:** Samsung Galaxy A71 SM-A715F, Android 13, ADB serial <your-device-serial>

### ADB commands used for verification

```bash
# Install debug APK
adb -s <your-device-serial> install -r app-debug.apk

# Launch app via SplashActivity (required — direct MainActivity launch is known to fail; see Known Issues below)
adb -s <your-device-serial> shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity

# Verify WorkManager SystemJobService is active
adb -s <your-device-serial> shell dumpsys jobscheduler | grep -i openvpn

# Watch for ProbeRequestWorker activity
adb -s <your-device-serial> logcat -v time -s OpenVPNGateApp:ProbeRequestWorker

# Check for probe worker errors or unexpected crashes
adb -s <your-device-serial> logcat -d | grep -E "(ProbeRequestWorker|FATAL EXCEPTION|AndroidRuntime)"

# Verify ServersV2Repository still fetches correctly after named("v2") Retrofit refactor (regression)
adb -s <your-device-serial> logcat | grep -E "(ServersV2Repository|ServersV2SyncCoordinator|fetchAllPages)"
```

### Logcat tag for probe worker

`OpenVPNGateApp:ProbeRequestWorker`

No output under this tag is expected at this stage — the enqueue trigger is wired in SUB-04. Absence of the tag is the correct result; any `E`-level or `WTF` log line under this tag would indicate an unexpected initialization failure.

### What was verified (SUB-02)

| Check | Result |
|---|---|
| APK install (`adb install -r`) | PASS |
| App launch: SplashActivity → MainActivity, no crash | PASS |
| ServersV2Repository fetches from network (named("v2") regression) | PASS |
| WorkManager SystemJobService active | PASS |
| ServerRefreshWorker completing normally | PASS |
| No ProbeRequestWorker error logs | PASS (expected — trigger not yet wired) |
| MainActivitySmokeTest instrumented suite | SEE KNOWN ISSUES BELOW |

### Probe E2E verification — deferred to SUB-04

End-to-end probe dispatch (enqueue → worker runs → HTTP POST → response handling) cannot be
verified until SUB-04 wires the VPN inactivity trigger and the backend endpoint is reachable in
the test environment. When SUB-04 is delivered:

1. Trigger a VPN inactivity condition
2. Observe `ProbeRequestWorker` logcat output: expect `Result.success()` on HTTP 202
3. Verify no duplicate workers enqueued for the same `serverId` (KEEP policy)
4. Verify 429 responses produce retry backoff (check WorkManager job re-schedule in `dumpsys jobscheduler`)

### Known Issues

**`MainActivitySmokeTest` failure (not caused by SUB-02) — RESOLVED in SUB-05**

All 7 `MainActivitySmokeTest` cases failed with `NoActivityResumedException` on this device when
run via `./gradlew connectedDebugAndroidTestApp`. The same failure rate (7/7) was reproduced on
the `dev` branch with no SUB-02 changes applied, confirming it was not a regression from SUB-02.

This issue was fixed in SUB-05. The actual root cause was `FLAG_ACTIVITY_NEW_TASK |
FLAG_ACTIVITY_CLEAR_TASK` flags on the `ActivityScenario` launch intents conflicting with
Espresso's lifecycle management. All 21 tests now pass on Samsung Galaxy A71 SM-A715F Android 13.
See `docs/guides/troubleshooting.md` for full details.

---

---

## MP-20260621 SUB-02 — Android SSE Client for Server-Push Notifications

**Story:** the ClickUp story
**Device tested:** Samsung Galaxy A71 SM-A715F, Android 13, ADB serial <your-device-serial>
**Backend endpoint:** `https://openvpngateclient.azurewebsites.net/api/v1/servers/events`

### Verification commands and log signals

Moved to keep one canonical copy. The SSE log-signal table and ADB verification one-liners are in
[../guides/adb-cookbook.md](../guides/adb-cookbook.md) — "SSE client verification"; the full
on-device procedure is in [../guides/how-to.md](../guides/how-to.md) — "Verify SSE client connection
on device". The behaviour itself is described in
[../features/server-sync.md](../features/server-sync.md).

## Logcat commands for SUB-01 regression validation

```bash
# Check for JSON parse errors
adb logcat -d | grep -E "(JsonSyntaxException|FATAL EXCEPTION)"

# Monitor server V2 loading
adb logcat | grep -E "(ServersV2Repository|SelectedCountryStore|ServersV2SyncCoordinator)"
```

---

## SUB-05 — Instrumented test fixes

**Story:** the ClickUp story

### What was fixed

1. **Test launch flags**: Removed `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` from
   `ActivityScenario.launch()` intents (test code only — no production code changed). These flags
   conflict with Espresso's lifecycle management and prevent the activity from reaching RESUMED state.

2. **Update dialog dismissal**: Added `dismissUpdatePromptIfVisible()` in test helpers to handle
   the async `PromptUpdate` effect that shows an update dialog on launch.

### Known Samsung device limitation

On Samsung SM-A715F (Android 13), `MainActivity` tests were previously reported to fail with
`NoActivityResumedException` due to Samsung's Freecess/GameSDK pausing the activity after RESUMED.

**Updated (SUB-05):** The Samsung whitelist workaround has been confirmed to work. After running:

```bash
adb shell cmd deviceidle whitelist +com.yahorzabotsin.openvpnclientgate
```

and applying the SUB-05 fix (removing `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` from
launch intents and adding `dismissUpdatePromptIfVisible()`), all 21 tests pass on Samsung Galaxy
A71 SM-A715F Android 13 (ADB serial <your-device-serial>) — 0 failures.

`SettingsActivity`, `DnsActivity`, and `ConnectionControlsView` tests continue to pass normally.

### Mi 9T Pro (MIUI / Android 11) limitation

On Xiaomi Mi 9T Pro (ADB serial b6e8f6bd, Android 11 / MIUI), instrumented tests are blocked
indefinitely by the Android 11 background activity start restriction. `ActivityScenario.launch()`
cannot launch activities when called from the instrumentation runner under MIUI's enforcement of
this restriction.

**Symptoms:**
- Tests do not fail with an error — they simply never start; the process hangs with no output.
- No timeout fires; the ADB process must be killed manually.

**What does not help:**
- `adb shell cmd deviceidle whitelist +com.yahorzabotsin.openvpnclientgate` — MIUI ignores the
  standard idle whitelist for this restriction.

**Workaround:** Use a Samsung or stock Android device for instrumented test runs. The Samsung
Galaxy A71 (Android 13) is the confirmed working test device for this project.

### Running the tests

```bash
# Run all instrumented tests
adb shell am instrument -w com.yahorzabotsin.openvpnclientgate.test/androidx.test.runner.AndroidJUnitRunner

# Run a specific test class
adb shell am instrument -w -e class com.yahorzabotsin.openvpnclientgate.mobile.MainActivitySmokeTest com.yahorzabotsin.openvpnclientgate.test/androidx.test.runner.AndroidJUnitRunner

# Run via Gradle (handles APK build + install)
./gradlew connectedDebugAndroidTestApp
```

---

## Per-App Locale Override (Samsung/One UI Workaround — SUB-07)

**Story:** the ClickUp story

On Samsung devices running One UI (and some other OEM skins), the system-wide locale settings command (`adb shell settings put system system_locales`) does not reliably propagate to running or restarted applications. The app's `mGlobalConfiguration` locale continues to reflect the previous setting even after force-stop and relaunch.

### Per-App Locale Override Technique

**For Android 13+**, use the `cmd locale` service to apply a per-app locale override directly without relying on system-wide settings:

```bash
# Set app locale to Polish (example)
adb shell cmd locale set-app-locales com.yahorzabotsin.openvpnclientgate --user 0 --locales pl-PL

# Force-stop the app to reload with the new locale
adb shell am force-stop com.yahorzabotsin.openvpnclientgate

# Relaunch the app
adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity

# Verify active locale in UI — you should see Polish strings (CZAS, STATUS, SERWER, MIASTO, URUCHOM POŁĄCZENIE, Ulubione, etc.)
```

### Clearing the Override

When done testing, clear the per-app locale to restore system locale behavior:

```bash
# Clear the per-app override (empty locales string)
adb shell cmd locale set-app-locales com.yahorzabotsin.openvpnclientgate --user 0 --locales ""

# Force-stop and relaunch to restore system locale
adb shell am force-stop com.yahorzabotsin.openvpnclientgate
adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
```

### Why This Works

The `cmd locale set-app-locales` command:
- Applies an override **per-app** rather than system-wide, bypassing One UI's interference with system settings
- Takes effect after the app is force-stopped and restarted
- Persists until explicitly cleared with an empty `--locales` string
- Is documented in Android 13+ frameworks; it is a stable platform API

### Device-Specific Notes

- **Samsung Galaxy A71 (Android 13)**: Confirmed working with this technique (CASE-SUB07-003 and 004).
- **Xiaomi Mi 9T Pro (MIUI / Android 11)**: Per-app locale override requires Android 13+; use system-wide `settings put system system_locales` instead (if it works on that device). This project's primary test device is Samsung; MIUI edge cases may require device-specific workarounds.
- **Stock Android / Pixel**: System-wide `settings put system system_locales` works reliably; per-app override is optional.

### Manual QA Evidence

See the ClickUp QA suite and `CASE-SUB07-004-pl-servers-locale.md` for a complete walkthrough of this technique in action across a Russian→Polish locale switch with localization verification on both countries and servers screens. (ClickUp QA evidence is gitignored and not tracked in the repo — the manual-e2e case files are the durable record.)

---

## Simulating a TV D-pad Long-Press via ADB (MIBOX4 / Android 9, API 28)

**Story:** the ClickUp story (retest round)

The favorites long-press dialog on TV (`FavoriteActionDialog`) is triggered by a plain `View.OnLongClickListener` set on each row — there is no custom `OnKeyListener` or manual timing logic. The framework itself promotes a held D-pad center/enter *key* press into `performLongClick()` after `ViewConfiguration.getLongPressTimeout()` (~500ms), exactly like a touch long-press. This means the long-press must genuinely be *held*, not just tapped.

**What does NOT work on this device (MIBOX4, Android 9 / API 28):**

- `adb shell input keyevent --longpress KEYCODE_DPAD_CENTER` — the `--longpress` flag is accepted without error but silently behaves like a normal short click (navigates into the row instead of opening the dialog). This flag appears unreliable/not honored on this old API 28 `input` command build.
- `adb shell sendevent /dev/input/eventN 1 28 1` (holding `KEY_ENTER`, scancode 28, on `/dev/input/event3`) — produced no effect at all in this run.

  > **Correction.** The original note here concluded that evdev injection "doesn't reach the app"
  > because `adb shell input` bypasses evdev via `InputManager`. That reasoning is wrong — it would
  > mean `sendevent` could never work, yet it is the documented working technique in
  > [device-qa-tv.md](device-qa-tv.md) and
  > [../guides/troubleshooting.md](../guides/troubleshooting.md), both using **`/dev/input/event2`
  > with scancode `353` (`KEY_SELECT`)**. This run used a different node *and* a different scancode,
  > which is the far more likely explanation for the null result. Resolve the node and scancode with
  > `getevent -pl` rather than assuming either.

**What works:** inject a synthetic **touchscreen** hold at the row's on-screen coordinates, exactly like the mobile long-press trick, even though this is a Leanback/D-pad-first TV app with no physical touchscreen:

```bash
# Hold at (x, y) for 800ms — same call used for the mobile PopupMenu long-press
adb -s <tv-serial> shell input swipe <x> <y> <x> <y> 800
```

This reaches the same `OnLongClickListener` as a genuine D-pad long-press because RecyclerView item views on this screen are also touch-clickable; Android's `input swipe`/`tap` commands inject `SOURCE_TOUCHSCREEN` events through the input dispatcher regardless of whether the device has real touch hardware, and the dispatcher routes them to whatever view is under the coordinates.

**Practical steps:**
1. Take a screenshot (`adb exec-out screencap -p > out.png`) to find the target row's coordinates.
2. `adb shell input swipe X Y X Y 800` on that row.
3. Screenshot again to confirm the `FavoriteActionDialog` appeared with the app-styled background (not stock).
4. Tap the resulting dialog's list item / Cancel button coordinates directly (also via `input tap X Y`) — the dialog is drawn at the screen coordinates visible in the screenshot.

**Restoring D-pad focus/state after touch injection:** touch events don't move D-pad focus, so after closing a dialog opened this way, a subsequent `KEYCODE_DPAD_DOWN`/`KEYCODE_DPAD_CENTER` may behave unexpectedly (focus can still be on the last D-pad-focused view, which is usually fine, but double-check with a screenshot before chaining more D-pad key events).

---

## `adb install -r` failing silently on a network-connected (Wi-Fi ADB) TV target — Windows/Git Bash

**Story:** US-14 (engine update) TV smoke retest.

On a TV device connected over `adb connect <ip>:5555` (as opposed to USB), `adb install -r <path>` from a Windows Git Bash shell failed with an empty error message (`adb.exe: failed to install ...:` with nothing after the colon), even though the APK built successfully and the same install flow works fine for USB-connected phones.

**What works:** push the APK to the device first, then install from the device-local path:

```bash
# MSYS_NO_PATHCONV=1 is required — otherwise Git Bash rewrites /data/local/tmp/... and
# /sdcard/... into Windows-style paths (e.g. "C:/Program Files/Git/data/local/tmp/...")
# before adb ever sees them, and the install/dump command fails with a Java
# IllegalArgumentException / "Unable to open file" pointing at the mangled path.
MSYS_NO_PATHCONV=1 adb -s <tv-ip>:5555 push tv-debug.apk /data/local/tmp/tv-debug.apk
MSYS_NO_PATHCONV=1 adb -s <tv-ip>:5555 shell pm install -r /data/local/tmp/tv-debug.apk
MSYS_NO_PATHCONV=1 adb -s <tv-ip>:5555 shell rm /data/local/tmp/tv-debug.apk
```

The same `MSYS_NO_PATHCONV=1` prefix is needed for any `adb shell` command that takes a device-side absolute path as an argument (e.g. `uiautomator dump /sdcard/window_dump.xml`, `cat /sdcard/window_dump.xml`) when running from Git Bash on Windows — without it, ripgrep-style greps against the dumped file silently return nothing because the dump itself was written to (or read from) the wrong, Windows-mangled path.

**Occasional transient failures:** even with the push+`pm install` path, the very first attempt sometimes returns a bare `Exit code 255` with no stderr at all; a retry of the identical command succeeds. Treat a silent/empty-error install failure as worth one retry before treating it as a real blocker.
