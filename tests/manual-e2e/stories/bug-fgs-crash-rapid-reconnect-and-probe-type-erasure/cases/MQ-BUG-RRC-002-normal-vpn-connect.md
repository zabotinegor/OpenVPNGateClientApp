# MQ-BUG-RRC-002 — Normal VPN connect (regression)

## AC
AC-5: No regression on normal connect

## Setup
1. App on MainActivity, status "Отключено"
2. Clear logcat: `adb -s <your-device-serial> shell logcat -c`

## Steps
1. Tap "НАЧАТЬ ПОДКЛЮЧЕНИЕ"
2. Grant VPN permission dialog ("ОК") if shown
3. Wait up to 15 s

## Expected
- Status changes from "Отключено" → "Подключено"
- `Engine level=LEVEL_CONNECTED detail=CONNECTED` in logcat
- Traffic counters (СКАЧАНО / ОТПРАВЛЕНО) show non-zero values
- Watchdog logs `healthy source=traffic trafficDelta=<n>` with n > 0

## Result (2026-06-25, <your-device-serial>)
PASS

## Evidence
```
22:04:00.925  I/OpenVPNGateApp:OpenVpnService: Engine level=LEVEL_CONNECTED detail=CONNECTED source=AIDL
22:04:13.198  I/OpenVPNGateApp:OpenVpnService: Watchdog: healthy source=traffic trafficDelta=1992 recovered=false
```
UI: СТАТУС="Подключено", СКАЧАНО=47.36 KB, ОТПРАВЛЕНО=50.13 KB, ГОРОД="Баку (+04:00 UTC)"
