# MQ-SUB03-004: Normal server sync log markers present — no regression

## Case
Verify that the server-list sync (splash preload → cache hit → main screen) proceeds as before,
with no new errors introduced by the SUB-03 DI changes.

## Steps
1. Capture logcat after app launch for the app PID:
   ```
   adb -s R58N849XQEY logcat -d --pid <PID> | grep -iE "Koin|splash|server|sync|preload|probe|CoreDi"
   ```
2. Confirm expected Timber markers are present

## Expected
- `SplashActivityCore: Starting server preload`
- `ServersV2SyncCoordinator: syncCountries(...)` or similar sync coordinator log
- `ServersV2Repository: getCountries[...]: cache hit` (or network fetch)
- No new error lines related to probe, DI, or HardProbeApiClient

## Result: PASS
- `OpenVPNGateApp:SplashActivityCore: Starting server preload. vpn_connected=false, cache_only=false` at 17:42:06
- `OpenVPNGateApp:ServersV2SyncCoordinator: syncCountries(forceRefresh=false, cacheOnly=false)` at 17:42:06
- `OpenVPNGateApp:ServersV2Repository: getCountries[locale=ru]: cache hit` at 17:42:06
- No probe, DI, or HardProbeApiClient error lines found
- Executed: 2026-06-16 17:43 UTC+3
