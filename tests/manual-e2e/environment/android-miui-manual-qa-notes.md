# Android MIUI Manual QA Notes

## Scope
Reusable notes for manual E2E execution on MIUI devices (validated on Mi 9 SE).

## Findings
- `adb shell uiautomator dump` can produce repeated `theme_compatibility.xml` stack traces on MIUI. The XML file is still created, but command output is noisy and can break scripted loops.
- For readiness checks, prefer `dumpsys activity activities` over UI dump polling.

## Recommended readiness commands
- Verify resumed activity:
  - `adb shell dumpsys activity activities | findstr /I "mResumedActivity ResumedActivity"`
- Launch app from exported splash:
  - `adb shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
- Capture screenshot evidence:
  - `adb exec-out screencap -p > manual-qa/<run-id>/screen.png`
- Read selected-country store evidence:
  - `adb shell run-as com.yahorzabotsin.openvpnclientgate ls shared_prefs`
  - `adb shell run-as com.yahorzabotsin.openvpnclientgate cat shared_prefs/vpn_selection_prefs.xml`

## Known blockers
- TV manual cases require a Leanback-capable target. Mobile device with `ro.build.characteristics=nosdcard` is not a valid TV substitute.
- Local `mobile-release-unsigned.apk` cannot be installed directly (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`). Use a signed release artifact for release-path install validation.
