# MQ-US15-004 — VPN connect/disconnect cycle regression (DEFAULT_V2 source)

## Preconditions
- App on MainActivity, "Client for OpenVPN Gate" (DEFAULT_V2) source selected, Australia/Melbourne
  server (2/2) selected

## Steps
1. Clear logcat: `adb logcat -c`
2. Tap "НАЧАТЬ ПОДКЛЮЧЕНИЕ" (start connection button, resource-id `start_connection_button`)
3. Wait for status to transition through "Подключение..." → "Подключено"
4. Confirm data counters increase (СКАЧАНО/ОТПРАВЛЕНО) and duration timer runs
5. Tap "ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ" (same button, now stop action)
6. Confirm status returns to "Отключено", duration resets to 00:00:00
7. Check logcat for FATAL/AndroidRuntime exceptions in the test time window

## Expected
- Full state sequence: Отключено → Подключение... → Подключено → Отключено
- Data counters non-zero while connected
- Zero fatal exceptions/crashes during the cycle

## Result: PASS
- Status reached "Подключено", duration 00:03:24, СКАЧАНО=741.87 KB, ОТПРАВЛЕНО=197.41 KB, VPN key
  icon visible in status bar.
- After tapping stop: status "Отключено", duration reset to 00:00:00, key icon gone.
- `adb logcat -d -v time | grep com.yahorzabotsin.openvpnclientgate` filtered to the test window
  (10:35–10:46) for FATAL/exception/crash: zero matches.

## Evidence
- Screenshots: phone_connecting2.png, phone_connected.png, phone_disconnected.png (retained
  locally, not committed)

## Run date
2026-07-20
