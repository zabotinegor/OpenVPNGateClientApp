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
