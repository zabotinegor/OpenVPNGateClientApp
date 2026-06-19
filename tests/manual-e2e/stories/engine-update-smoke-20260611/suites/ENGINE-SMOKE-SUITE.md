# Suite: Engine Update Smoke — 2026-06-11

**Overall result: PASS**

Branch: `fix/sync-agent-assets`
Engine submodule: `736ebfe1 → a83da9ff`
Build: `mobile-debug.apk` (versionCode 63)
Device: Samsung Galaxy A71 SM_A715F (R58N849XQEY), Android 13
Date: 2026-06-11
Tester: Claude (automated ADB)

## Results

| Case | Title | Result |
|------|-------|--------|
| SMOKE-01 | Cold launch, splash → main | PASS |
| SMOKE-02 | Server list load (DEFAULT_V2) | PASS |
| SMOKE-03 | VPN connect + watchdog + disconnect | PASS |
| SMOKE-04 | Notification tap → MainActivity (US-11) | PASS |
| SMOKE-05 | No fatal exceptions | PASS |

## Key evidence

- Engine `VPN_GENERATE_CONFIG` state reached (new SWIG API path executed)
- `LEVEL_CONNECTED / CONNECTED` with watchdog `trafficDelta > 0`
- Cipher line absent from config (expected: upstream compat-mode change)
- `topResumedActivity = MainActivity` after notification tap (no crash)
- VPN key icon present in status bar during connected session
- City `Сидней (+10:00 UTC)` displayed correctly (US-09 intact)
- Pause button visible in CONNECTED state (US-10/VPN-PAUSE intact)
- Zero fatal exceptions in full session

## Setup note

Device was initially 100% full (366 MB free, APK 130 MB). User freed ~2.9 GB before run. `svc power stayon usb` was used during test to prevent screen timeout; restored after.
