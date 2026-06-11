# Testing Knowledge Index

Purpose: keep short, reusable, non-secret pointers discovered by Manual QA runs so future agents can find proven setup/workaround paths quickly.

Scope:
- This file is an index, not a full runbook.
- Source of truth stays in each tested service repository (for example `tests/manual-e2e/environment/*.md`).
- Keep entries concise and link-first.

Entry template:

## <Short title>
- Service repo: <owner/repo or local path>
- Surface: web | android | api | db | desktop | shell | worker | integration-service
- When to use: <trigger/symptom>
- Reusable workaround/setup: <short summary>
- Source-of-truth doc: <path in tested service repo>
- Last validated: <YYYY-MM-DD>
- Notes: <optional non-secret hints>

---

## Seed entry
- Service repo: N/A
- Surface: N/A
- When to use: Bootstrap only
- Reusable workaround/setup: No historical entries yet.
- Source-of-truth doc: N/A
- Last validated: 2026-05-13
- Notes: Add entries after each Manual QA run when reusable knowledge is discovered.

---

## Samsung Galaxy A71 — screen locks during ADB session

- Service repo: d:\Apps\OpenVPNClient\OpenVPNClientClientApp
- Surface: android
- When to use: Any ADB UI interaction on SM_A715F (R58N849XQEY)
- Reusable workaround/setup: Run `adb shell svc power stayon usb` before any tap sequence to prevent screen timeout; restore with `adb shell svc power stayon false` after test. Also: always run `input keyevent 224` (screen on) + `input keyevent 82` (unlock) before `am start`; omitting this before a second `am start` causes `LaunchState: UNKNOWN` and `exit MainActivity` in log because MainActivity window is invisible behind the lock screen.
- Source-of-truth doc: tests/manual-e2e/environment/android-miui-manual-qa-notes.md
- Last validated: 2026-06-11
- Notes: Without stay-awake, screen locks in ~30 s. `dumpsys activity activities` still shows correct topResumedActivity even when screen is locked.

---

## Notification shade — VPN notification only visible after scroll on SM_A715F

- Service repo: d:\Apps\OpenVPNClient\OpenVPNClientClientApp
- Surface: android
- When to use: Tapping the VPN foreground notification on SM_A715F (One UI 5, Android 13)
- Reusable workaround/setup: Use `adb shell cmd statusbar expand-notifications` (more reliable than swipe on One UI) to open shade. VPN notification typically appears below personal notifications — scroll or use a second swipe `input swipe 540 1600 540 600 600` before looking for it. Tap approximate y-coordinate ~1300 when shade is in mid-scroll state showing the VPN card. `uiautomator dump` may fail with `ERROR: could not get idle state` in notification shade — use coordinate fallback and validate via `dumpsys activity activities`.
- Source-of-truth doc: tests/manual-e2e/environment/android-miui-manual-qa-notes.md
- Last validated: 2026-06-11
- Notes: `content-desc="Уведомление Client for OpenVPN Gate: Австралия"` is the notification icon node; the tappable card is the parent row higher up. VPN key icon visible in status bar confirms foreground service is active even when card scrolled off screen.

---

## Engine update smoke: `adb exec-out screencap -p` produces corrupt PNG on SM_A715F

- Service repo: d:\Apps\OpenVPNClient\OpenVPNClientClientApp
- Surface: android
- When to use: Capturing screenshots via ADB on SM_A715F
- Reusable workaround/setup: Use `adb shell screencap -p /sdcard/name.png` then `adb pull /sdcard/name.png local/path.png` instead of `adb exec-out screencap -p > local/path.png`. The exec-out pipe produces garbled bytes (UTF-16 LE header) on this device.
- Source-of-truth doc: tests/manual-e2e/environment/android-miui-manual-qa-notes.md
- Last validated: 2026-06-11
- Notes: Pull approach reliably produces valid PNG at correct size.
