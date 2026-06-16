# SUB03-CORE: Manual QA Suite — Hardprobe Retrofit API Client

## Story
SUB-03: Hardprobe Retrofit API client
Branch: feature/SUB-03-hardprobe-retrofit-api-client
Device: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)

---

## Run 1 — Initial QA (2026-06-16 17:41 UTC+3)

| Case | Title | Result |
|------|-------|--------|
| MQ-SUB03-001 | APK installs cleanly | PASS |
| MQ-SUB03-002 | App launches to main screen without crash | PASS |
| MQ-SUB03-003 | No DI-related fatal exception in logcat | PASS |
| MQ-SUB03-004 | Normal server sync log markers present (no regression) | PASS |

**Evidence summary**

- **Install**: `adb install -r mobile-debug.apk` → `Success` (APK 2026-06-16 16:42:59, 132 MB)
- **Launch**: `am start .mobile.SplashActivity` → PID 18259 alive after 10 s
- **Main screen**: `MainActivityCore: onCreate called` + `ScreenFlow: enter MainActivity` at 17:42:11
- **DI**: zero FATAL / NoBeanDefFoundException / KoinException / HardProbeApiClient errors in logcat
- **Server sync**: `SplashActivityCore: Starting server preload`, `syncCountries(forceRefresh=false)`, `getCountries[locale=ru]: cache hit` — all present, no new errors

**Overall: PASS**

---

## Run 2 — Retest after review-comment fixes (2026-06-16 20:17 UTC+3)

Scope: verify acceptance criteria still hold after `fix(probe): correct KDoc and add test locking strict 202-only mapping` (ab91a52). Review fix changes: KDoc comment correction in `HardProbeApiClient.kt` + new unit test for HTTP 200 → Error(200); no runtime behavior change.

| Case | Title | Result |
|------|-------|--------|
| MQ-SUB03-001 | APK installs cleanly | PASS |
| MQ-SUB03-002 | App launches to main screen without crash | PASS |
| MQ-SUB03-003 | No DI-related fatal exception in logcat | PASS |
| MQ-SUB03-004 | Normal server sync log markers present (no regression) | PASS |

**Evidence summary**

- **Install**: `adb -s R58N849XQEY install -r mobile-debug.apk` → `Performing Streamed Install / Success`
- **Launch**: `am start .mobile.SplashActivity` → PID 26025 alive; no error
- **Main screen**: `MainActivityCore: onCreate called` + `ScreenFlow: enter MainActivity` at 20:18:01
- **DI**: zero FATAL / NoBeanDefFoundException / KoinException / HardProbeApiClient / ProbeApi errors for PID 26025
- **Server sync**: `SplashActivityCore: Starting server preload. vpn_connected=false, cache_only=false` at 20:17:56; `ServersV2SyncCoordinator: syncCountries(forceRefresh=false)` at 20:17:56; `ServersV2Repository: getCountries[locale=ru]: fetching from network` → `cache hit` at 20:18:01; `syncSelectedCountryServers: synced country=Республика Литва servers=2`; no probe, DI, or HardProbeApiClient error lines

**Overall: PASS**

All 4 cases passed on retest. Review-fix commit (KDoc + strict-202 test) does not affect runtime behavior. DI graph, app launch, and server sync all confirmed clean.
