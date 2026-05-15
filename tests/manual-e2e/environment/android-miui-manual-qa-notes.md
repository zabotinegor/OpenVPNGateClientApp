# Android MIUI Manual QA Notes

## Scope
Reusable notes for manual E2E execution on MIUI devices (validated on Mi 9 SE).

## Findings
- `adb shell uiautomator dump` can produce repeated `theme_compatibility.xml` stack traces on MIUI. The XML file is still created, but command output is noisy and can break scripted loops.
- For readiness checks, prefer `dumpsys activity activities` over UI dump polling.
- When the launchable app is present only for the owner user, activate it first with `adb shell pm install-existing --user 0 com.yahorzabotsin.openvpnclientgate` before launching `com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`.
- For SYSTEM-language assertions, treat `persist.sys.locale` as the runtime locale source on MIUI devices. `ro.product.locale` can still reflect a factory/default locale and may differ from the active runtime value.

## Recommended readiness commands
- Verify resumed activity:
  - `adb shell dumpsys activity activities | findstr /I "com.yahorzabotsin.openvpnclientgate/.mobile.MainActivity"`
- Launch app from exported splash:
  - `adb shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
- Confirm transition to main screen after splash:
  - `adb shell dumpsys activity activities | findstr /I "com.yahorzabotsin.openvpnclientgate/.mobile.MainActivity"`
- Capture screenshot evidence:
  - `adb exec-out screencap -p > manual-qa/<run-id>/screen.png`
- Read selected-country store evidence:
  - `adb shell run-as com.yahorzabotsin.openvpnclientgate ls shared_prefs`
  - `adb shell run-as com.yahorzabotsin.openvpnclientgate cat shared_prefs/vpn_selection_prefs.xml`
- Capture locale diagnostics for SYSTEM-language checks:
  - `adb shell getprop persist.sys.locale`
  - `adb shell getprop ro.product.locale`

## Real-device observable flow (MIUI)
Use this sequence when the tester expects visible UI actions on the phone screen:

1. Wake/unlock and launch from exported splash:
   - `adb -s <device> shell input keyevent 224`
   - `adb -s <device> shell input keyevent 82`
   - `adb -s <device> shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
2. Verify the app resumed `MainActivity`:
  - `adb -s <device> shell dumpsys activity activities | findstr /I "com.yahorzabotsin.openvpnclientgate/.mobile.MainActivity"`
3. Dump current UI tree for deterministic tap targets:
   - `adb -s <device> shell uiautomator dump /sdcard/current_ui.xml`
   - `adb -s <device> pull /sdcard/current_ui.xml manual-qa/<run-id>/current_ui.xml`
4. If update dialog is shown (`ОБНОВИТЬ/ОТМЕНА/ЧТО НОВОГО`), use one of two paths:
   - Open release notes: tap `ЧТО НОВОГО`, capture screenshot, then back.
   - Dismiss dialog: tap `ОТМЕНА`, then continue normal navigation checks.
5. Capture screenshot after each visible transition:
   - splash/main, update dialog, "Что нового" page, and main-after-return.

## Launcher activation note
- If `pm list packages` shows `com.yahorzabotsin.openvpnclientgate` but the launcher still fails to start, run `adb shell pm install-existing --user 0 com.yahorzabotsin.openvpnclientgate` and retry the `/.mobile.SplashActivity` launch.
- For UI evidence, wait until `dumpsys activity activities` shows `mResumedActivity` pointing at `com.yahorzabotsin.openvpnclientgate/.mobile.MainActivity`; splash-only screenshots are not sufficient for list-label assertions.

## Source-switch regression pattern (DEFAULT_V2 / LEGACY / VPNGATE)
Use this deterministic flow for source-specific fetch validation without UI flakiness in settings navigation:

1. Write `user_settings.xml` via `run-as tee`:
   - `"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>...<string name='server_source'>LEGACY</string>..." | adb shell run-as com.yahorzabotsin.openvpnclientgate tee /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/user_settings.xml`
2. Clear source caches before launch:
   - `adb shell run-as com.yahorzabotsin.openvpnclientgate rm -f /data/data/com.yahorzabotsin.openvpnclientgate/cache/v2_*.json /data/data/com.yahorzabotsin.openvpnclientgate/cache/servers_*.csv /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/servers_v2_cache.xml /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/server_cache.xml`
3. Clear logcat, force-stop app, and relaunch splash.
4. Wait ~15-20 seconds, then collect screenshot, UI XML, and filtered logcat markers.
5. Validate by logs:
   - `DEFAULT_V2`: `ServersV2Repository: getCountries: fetching from network`
   - `LEGACY`: `ServerRepository: Cache miss/stale. Fetching servers. Source=LEGACY`
   - `VPNGATE`: `ServerRepository: Cache miss/stale. Fetching servers. Source=VPNGATE`

## Known UI blockers and workarounds
- `MainActivity` is not exported; direct launch via `adb shell am start -W .../.mobile.MainActivity` returns `SecurityException`.
  - Workaround: launch exported `SplashActivity` and wait/check transition to `MainActivity` via `dumpsys`.
- Startup can be blocked by update dialog before drawer/settings checks.
  - Workaround: explicitly handle the dialog first (`ОТМЕНА` or `ЧТО НОВОГО` then back), then continue navigation checks.
- MIUI `uiautomator dump` prints `theme_compatibility.xml` stack trace noise.
  - Workaround: treat stderr as non-fatal when `UI hierchary dumped to ...` is present and XML pull succeeds.
- SYSTEM-language locale assertions can be misread when using only `ro.product.locale`.
  - Workaround: always capture both locale props and prioritize `persist.sys.locale` as the active runtime locale source.

## Settings automation pattern (US-08)
- On Mi 9 SE layout, the navigation drawer item `Settings` is reachable from main after opening drawer (`Open navigation drawer` button bounds near `[0,129][144,297]`) and tapping the `Settings` row (around y=537..681 in drawer XML).
- In `SettingsActivity`, language and source controls are directly addressable by resource IDs in UI dump:
  - Language header: `id/language_header`
  - Language options: `id/language_en`, `id/language_ru`, `id/language_pl`, `id/language_system`
  - Server source header: `id/server_header`
  - Source options: `id/server_default_v2`, `id/server_default`, `id/server_vpngate`, `id/server_custom`
- Reliable flow for automation: dump current UI XML, resolve bounds for target resource ID, then tap center of bounds instead of hardcoded coordinates.

## Known blockers
- TV manual cases require a Leanback-capable target. Mobile device with `ro.build.characteristics=nosdcard` is not a valid TV substitute.
- Local `mobile-release-unsigned.apk` cannot be installed directly (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`). Use a signed release artifact for release-path install validation.
- On some MIUI sessions, `:mobile:connectedDebugAndroidTest` can stall at `99% EXECUTING` after printing `Starting N tests ...`. If this happens, collect partial runner artifacts from `src/mobile/build/outputs/androidTest-results/connected/debug/` (XML + `testlog/test-results.log`) and terminate the stuck Gradle session before retrying.
- In some sessions Gradle can also stall during daemon/configuration before tests start.
  - Workaround: run manual real-device observable flow above and attach screenshot + UI XML evidence.
