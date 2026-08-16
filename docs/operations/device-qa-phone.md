# Android ADB — Manual QA Runbook for OpenVPN Gate Client

## Device Setup

- ADB serial: <your-device-serial> (MIUI/Xiaomi device in secondary space)
- Package: `com.yahorzabotsin.openvpnclientgate`
- Launch activity: `.mobile.SplashActivity`

## Known Workarounds

### Package listing fails with SecurityException (user 150)
`adb shell pm list packages` fails with `Shell does not have permission to access user 150`.
Use instead:
```
adb -s <your-device-serial> shell dumpsys package com.yahorzabotsin.openvpnclientgate | grep -i "package\|version"
```
Alternative that also works: `adb shell pm list packages --user 0` (explicit user 0 avoids the
default-user resolution that triggers the SecurityException on some multi-user devices).

### Launching the app without a resolvable explicit activity name
`adb shell am start -n <pkg>/.SplashActivity` can fail with `does not exist` even though the app is
installed, because the manifest-declared short name resolves differently per launcher/build variant.
Use the launcher category instead, which always resolves correctly:
```
adb -s <your-device-serial> shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LAUNCHER 1
```
Then confirm with `adb shell dumpsys window | grep mCurrentFocus` to see the actual resolved activity name.

### Activity resolution
Use `cmd package resolve-activity` to find the launchable activity:
```
adb -s <your-device-serial> shell cmd package resolve-activity --brief com.yahorzabotsin.openvpnclientgate
```
Result: `.mobile.SplashActivity`

### Splash stalls (screen locked)
If `SplashActivity` doesn't transition to `MainActivity`, the device screen may be locked.
Fix:
```
adb -s <your-device-serial> shell input keyevent 224   # wake screen
```

### uiautomator dump — no PCRE grep on device
`grep -P` fails on the device shell. Use `grep -E` for extended regex.
```
adb -s <your-device-serial> shell "uiautomator dump /sdcard/ui.xml && cat /sdcard/ui.xml" | grep -E "pattern"
```

### `--tests` flag not supported on aggregate Gradle task
`./gradlew.bat testDebugUnitTestApp --tests "*.SomeTest"` fails.
Run the full suite: `./gradlew.bat testDebugUnitTestApp`

### VPN force-stop
```
adb -s <your-device-serial> shell am force-stop com.yahorzabotsin.openvpnclientgate
```

### App data wipe (fresh-install simulation without reinstall)
```
adb -s <your-device-serial> shell pm clear com.yahorzabotsin.openvpnclientgate
```

### App theme and language are in-app settings, NOT tied to system dark mode / system locale
`adb shell cmd uimode night yes|no` and changing the device system locale have **no effect** on this
app's rendered theme or language. The app has its own independent settings:
- Drawer menu -> Ustawienia/Настройки/Settings -> **Motyw/Тема/Theme** (`Motyw systemu`/`Jasny`/`Ciemny` —
  System/Light/Dark)
- Drawer menu -> Ustawienia/Настройки/Settings -> **Język/Язык/Language** (`Język systemu`, `English`,
  `Русский`, `Polski`, …)
Both must be toggled from inside the app's own Settings screen to reliably test light/dark theme or
locale-formatting behavior. Number formatting (decimal separator) was independently verified to stay
period-based (`0.09`, not `0,09`) under both `pl` and `ru` in-app language selection, confirming the
formatter is locale-independent regardless of the in-app language setting.

### Tap coordinates for evidence screenshots — always compute from the real device resolution, not the chat-displayed image size
`adb exec-out screencap -p` captures at native resolution (e.g. 1080x2400, confirm with
`adb shell wm size`). When a screenshot is shown back in chat it is often *displayed* scaled down (e.g.
900x2000), and picking tap coordinates directly off the displayed image lands on the wrong element. Two
reliable options:
1. Multiply displayed-image pixel coordinates by the stated scale factor (e.g. x1.2 for 900->1080) before
   calling `adb shell input tap`.
2. More robust: pull a `uiautomator dump` and read the exact `bounds="[x1,y1][x2,y2]"` for the target
   `resource-id`, then tap the bounds' center in native-resolution coordinates:
```
adb -s <your-device-serial> shell uiautomator dump //sdcard/window_dump.xml
adb -s <your-device-serial> pull //sdcard/window_dump.xml ./window_dump.xml
grep -o 'resource-id="[^"]*button[^"]*"[^>]*bounds="[^"]*"' window_dump.xml
```
Note the `//sdcard/...` double-slash form — a single `/sdcard/...` path gets mangled by Git Bash/MSYS
path translation on Windows into something like `C:\Program Files\Git\sdcard\...`.

### Favorites state inspection/reset (debug builds only)
Favorites persist in `shared_prefs/favorites_prefs.xml` (`favorite_server_ids`, `favorite_country_codes`).
Inspect without root via run-as (works on debug builds):
```
adb -s <your-device-serial> shell "run-as com.yahorzabotsin.openvpnclientgate cat shared_prefs/favorites_prefs.xml"
```
Use this to verify clean pre-test state (empty `<set>` elements) and post-test cleanup.
Note: favorites UI strings are localized for ru/pl — on Russian device locales the header and menu items
render as "Избранное" / "Добавить в избранное" / "Удалить из избранного"; on Polish, "Ulubione" /
"Dodaj do ulubionych" / "Usuń z ulubionych". Match uiautomator dumps against the localized strings, not English.

## Useful Log Filters

### Server selection + counter
```
adb -s <your-device-serial> logcat -d 2>&1 | grep "OpenVPNGateApp" | grep -E "(chosenIndex|ensureIndex|Session attempt|ConnectionControlsView|pendingUser|Server sel)"
```

### Full connect-flow trace
```
adb -s <your-device-serial> logcat -d 2>&1 | grep "OpenVPNGateApp" | grep -E "(CountryServersInteractor|MainViewModel|MainConnectionInteractor|SelectedCountryStore|OpenVpnService)" | grep -E "(chosenIndex|ensureIndex|Session attempt|Server sel|getLastSuccessful|saveLastStart|prepareStart)"
```

### Git Bash mangles `/sdcard/...` paths in `adb pull`/`push`
On Windows with Git Bash, `adb pull /sdcard/ui.xml <dest>` fails with
`failed to stat remote object 'C:/Program Files/Git/sdcard/ui.xml'` because Git Bash's POSIX-path
auto-conversion rewrites the device-side path as if it were a local one. Fix: set
`export MSYS_NO_PATHCONV=1` before the `adb pull`/`push` call (or prefix the single command with
`MSYS_NO_PATHCONV=1 adb ...`).

### Forcing landscape to prove a portrait lock (phone, API 26+)
To verify an activity is portrait-locked without physically rotating the device, capture the
device's actual pre-test values first, then restore those captured values afterward — do not
hard-code `1`/`0`, since a tester's device may already have auto-rotate off or a non-zero
`user_rotation`, and hard-coded restores would leave it in a different state than before the test:
```
ORIG_ACCEL=$(adb shell settings get system accelerometer_rotation)
ORIG_ROT=$(adb shell settings get system user_rotation)
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell dumpsys window displays | grep -o "mRotation=[A-Z0-9_]*"
adb shell settings put system user_rotation $ORIG_ROT
adb shell settings put system accelerometer_rotation $ORIG_ACCEL
```
If the activity's `requestedOrientation` is portrait, `mRotation` stays `ROTATION_0`/`0` despite the
forced `user_rotation=1`. Always restore both settings to the values captured before the test (not
hard-coded `1`/`0`) after each screen, to leave the device in its actual original state.

### Simulating a tablet (`smallestScreenWidthDp`) on a real phone
`adb shell wm density <n>` changes the effective `smallestScreenWidthDp` without a different device:
for a 1080px-wide physical display, `wm density 240` yields `sw720dp` (`1080 * 160 / 240 = 720`),
confirmable via `adb shell dumpsys activity activities | grep -o "sw[0-9]*dp"`. Relaunch the app after
changing density so activities re-evaluate the new configuration.

Capture any pre-existing density override before simulating, then restore exactly that state
afterward — a reusable QA device may already have a tester's own override active, and `adb shell wm
density` only prints an `Override density: <n>` line when an override is actually set (confirmed live:
with no override it prints just `Physical density: 420`; after `adb shell wm density 240` it prints
both `Physical density: 420` and `Override density: 240`). Hard-coding `wm density reset` would discard
that pre-existing override instead of restoring it:
```
ORIG_DENSITY_LINE=$(adb shell wm density | grep "Override density:")
adb shell wm density 240
# ... run the test ...
if [ -n "$ORIG_DENSITY_LINE" ]; then
  ORIG_DENSITY=$(echo "$ORIG_DENSITY_LINE" | grep -o "[0-9]*")
  adb shell wm density $ORIG_DENSITY
else
  adb shell wm density reset
fi
```
This procedure never sets a `wm size` override — nothing above calls `wm size <WxH>` — so there is
nothing to restore there. Do not run `wm size reset` after this test; leave `wm size` untouched.

### Navigation drawer open/closed state persists across activity back-navigation
On both phone and TV, if the nav drawer is opened from `MainActivity`, then a secondary activity is
launched from it, then the user presses Back, the drawer reopens already-open (content-desc reads
"Zamknij/Закрыть panel nawigacji" = "close", not "open") with focus already on the just-used nav
item. Don't blindly tap the hamburger icon coordinates a second time — dump the UI first and check
the hamburger's content-desc/focused node to know whether the drawer is already open, otherwise a
blind tap can land on unrelated content behind an unexpectedly-open drawer and navigate somewhere
unintended.

### Reaching WebViewActivity (mobile) via real navigation, not `adb am start`
`WebViewActivity` is non-exported. `AboutActivity`'s external links (Privacy Policy, Terms, Source)
open an external browser via `Intent.createChooser`/`startActivity` when one is installed, and only
fall back to in-app `WebViewActivity` when no exported browser handles the URL or on TV — so on a
normal phone with Chrome installed, About links do NOT reach `WebViewActivity`. The reliable path is
the drawer's "Update"/"Aktualizuj" item: if an update is available it opens a dialog with a
"CO NOWEGO"/"What's New" button that opens `WebViewActivity` with the release-notes HTML
(`MainActivityCore.openUpdateChangelog`).

## Known Environmental Behaviour

### configData strings are dynamic (API returns different content per fetch)
The OpenVPN Gate server API returns config strings (`configData`) that change with each fetch
(likely include session nonces or timestamps). As a result:
- ViewModel state may hold a stale `configData` from the time of server selection
- Subsequent SSE syncs refresh the store with new `configData` strings
- `ensureIndexForConfig` config-match fails → falls back to IP-only → resets index to 0 (first server)
- This manifests as `matched by ip index=1/N` in logs even after user selects server N/N

This is a fundamental constraint for all Belarus/multi-server IP tests.

### Belarus has 3 servers, all sharing IP 213.184.224.127
All config-match tests must use Belarus because it is the only known country with multiple servers
sharing the same IP, which exercises the IP-vs-config disambiguation logic.

### SSE fires `servers-changed` on connection open and repeatedly during session
The SSE client fires `servers-changed` events causing the store to be refreshed every few seconds.
During any delay between server selection and Connect tap, the store will be updated multiple times.

### Bringing a backgrounded app instance back to foreground (not a fresh launch)
`adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.MainActivity` fails with
`SecurityException: ... not exported from uid <app>` — `MainActivity` is intentionally not exported.
To simulate the user tapping the home-screen icon again (resumes the existing task instead of
recreating it) use the same launcher-category monkey trick as app launch:
```
adb -s <your-device-serial> shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LAUNCHER 1
```
Used this way (after `adb shell input keyevent KEYCODE_HOME` to background the app) to validate
that the VPN connection UI stays accurate when foregrounding mid-`CONNECTING` state — no crash, no
stale UI (bug-autoswitch-stale-push-stall manual QA, 2026-08-06, Samsung Galaxy A71 SM-A715F).

### Airplane-mode toggle for forcing a connection drop — allow settle time before reconnect
`adb shell cmd connectivity airplane-mode enable` / `disable` is the reliable way to force a
mid-connection network drop for `ServerAutoSwitcher` regression testing. However, immediately after
`disable`, the engine's own connectivity check can still observe `LEVEL_NONETWORK` for a few seconds
while Wi-Fi re-associates, even though `adb shell ping -c1 8.8.8.8` from the device shell already
succeeds. A Connect tap during that narrow window correctly triggers the (expected, pre-existing)
immediate-switch/no-alternative-disconnect path — it is not a bug in the app, just a transient OS-level
network readiness gap. If a connect attempt right after re-enabling network unexpectedly disconnects
via `LEVEL_NONETWORK`, wait a few more seconds and retry before treating it as a regression.
