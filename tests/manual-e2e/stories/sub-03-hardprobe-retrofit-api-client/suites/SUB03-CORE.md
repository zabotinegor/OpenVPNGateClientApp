# SUB03-CORE: Manual QA Suite — Hardprobe Retrofit API Client

## Story
SUB-03: Hardprobe Retrofit API client
Branch: feature/SUB-03-hardprobe-retrofit-api-client
Device: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)
Run date: 2026-06-16

## Cases

| Case | Title | Result |
|------|-------|--------|
| MQ-SUB03-001 | APK installs cleanly | PASS |
| MQ-SUB03-002 | App launches to main screen without crash | PASS |
| MQ-SUB03-003 | No DI-related fatal exception in logcat | PASS |
| MQ-SUB03-004 | Normal server sync log markers present (no regression) | PASS |

## Evidence summary

- **Install**: `adb install -r mobile-debug.apk` → `Success` (APK 2026-06-16 16:42:59, 132 MB)
- **Launch**: `am start .mobile.SplashActivity` → PID 18259 alive after 10 s
- **Main screen**: `MainActivityCore: onCreate called` + `ScreenFlow: enter MainActivity` at 17:42:11
- **DI**: zero FATAL / NoBeanDefFoundException / KoinException / HardProbeApiClient errors in logcat
- **Server sync**: `SplashActivityCore: Starting server preload`, `syncCountries(forceRefresh=false)`, `getCountries[locale=ru]: cache hit` — all present, no new errors

## Overall: PASS

All 4 cases passed. DI graph initialized cleanly with the new `HardProbeApiClient` Koin binding. No crashes or regressions observed.
