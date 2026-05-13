---
id: US-06-MANUAL-SUITE
title: US-06 manual QA suite
purpose: Regression
---

## Cases
1. US-06-MQ-01 - Mobile shared startup and metadata routing flow - `../cases/us-06-mq-01-mobile-shared-flows.md`
2. US-06-MQ-02 - TV parity for startup, source switch, refresh, and metadata actions - `../cases/us-06-mq-02-tv-shared-flows.md`
3. US-06-MQ-03 - Mobile regression of server fetch across DEFAULT_V2, LEGACY, and VPNGATE - `../cases/us-06-mq-03-mobile-server-source-fetch-regression.md`

## Execution Result
- Result: BLOCKED
- Date: 2026-05-13
- Commit: d9004f308b91061ddc3e00bc39b65d065a8608e7

### Observed outcomes
- Mobile splash launch succeeded on Mi 9 SE (`am start -W` returned Status=ok).
- Direct shell open of AboutActivity failed with expected `SecurityException` (`not exported`).
- Mobile instrumentation run `:mobile:connectedDebugAndroidTest` with class filter started (7 tests detected), then hung at 99% and required termination.
- Real-device observable run executed with fresh evidence:
  - Launched exported `SplashActivity` and verified transition to `.mobile.MainActivity` via `dumpsys`.
  - Captured fresh startup/current screen screenshots and UI XML dumps.
  - Handled update dialog and opened `Что нового`; captured release-notes WebView UI dump and screenshot.
  - Returned to `.mobile.MainActivity` and captured post-dialog state.
- Partial test artifact emitted:
  - `TEST-Mi 9 SE - 11-_mobile-.xml` contains only 1 testcase record.
  - `test-results.log` contains only start event for first test and no completion code.
- TV parity execution blocked because connected device lacks Leanback (`ro.build.characteristics=nosdcard`, no `android.software.leanback`).
- Mobile source-fetch regression run completed for three persisted sources:
  - `DEFAULT_V2`: `ServersV2Repository` performed network fetch (`getCountries: fetching from network`).
  - `LEGACY`: `ServerRepository` fetched CSV with `Source=LEGACY` and cached result (`items=1527`).
  - `VPNGATE`: `ServerRepository` fetched CSV with `Source=VPNGATE` and cached result (`items=99`).

### Evidence index
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-splash-main.png
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-about.png
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-logcat-key.txt
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-splash-main-fresh.png
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-current-screen-fresh.png
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-whats-new-fresh.png
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-main-after-dialog-fresh.png
- manual-qa/2026-05-13-us06-build-time-url-unification/us06_ui.xml
- manual-qa/2026-05-13-us06-build-time-url-unification/us06_ui_after.xml
- manual-qa/2026-05-13-us06-build-time-url-unification/us06_ui_whats_new.xml
- src/mobile/build/outputs/androidTest-results/connected/debug/TEST-Mi 9 SE - 11-_mobile-.xml
- src/mobile/build/outputs/androidTest-results/connected/debug/Mi 9 SE - 11/testlog/test-results.log
- src/mobile/build/outputs/androidTest-results/connected/debug/Mi 9 SE - 11/logcat-com.yahorzabotsin.openvpnclientgate.mobile.MainActivitySmokeTest-openDrawer_and_clickSettings_opensSettingsScreen.txt
- manual-qa/2026-05-13-us06-server-source-regression/DEFAULT_V2-logcat-filtered.txt
- manual-qa/2026-05-13-us06-server-source-regression/DEFAULT_V2-screen.png
- manual-qa/2026-05-13-us06-server-source-regression/DEFAULT_V2-ui.xml
- manual-qa/2026-05-13-us06-server-source-regression/DEFAULT_V2-user_settings.xml
- manual-qa/2026-05-13-us06-server-source-regression/LEGACY-logcat-filtered.txt
- manual-qa/2026-05-13-us06-server-source-regression/LEGACY-screen.png
- manual-qa/2026-05-13-us06-server-source-regression/LEGACY-ui.xml
- manual-qa/2026-05-13-us06-server-source-regression/LEGACY-user_settings.xml
- manual-qa/2026-05-13-us06-server-source-regression/VPNGATE-logcat-filtered.txt
- manual-qa/2026-05-13-us06-server-source-regression/VPNGATE-screen.png
- manual-qa/2026-05-13-us06-server-source-regression/VPNGATE-ui.xml
- manual-qa/2026-05-13-us06-server-source-regression/VPNGATE-user_settings.xml
