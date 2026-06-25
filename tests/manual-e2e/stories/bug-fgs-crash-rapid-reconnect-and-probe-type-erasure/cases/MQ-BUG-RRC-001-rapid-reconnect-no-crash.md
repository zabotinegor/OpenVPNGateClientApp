# MQ-BUG-RRC-001 — Rapid reconnect: no crash on 3rd connect attempt

## AC
AC-1: 3rd rapid-connect crash no longer occurs
AC-2: No `RemoteServiceException` in logcat for `OpenVpnService`

## Setup
1. Install debug APK on device
2. Clear logcat: `adb -s <your-device-serial> shell logcat -c`
3. Launch app and wait for MainActivity

## Steps
1. Tap "НАЧАТЬ ПОДКЛЮЧЕНИЕ" → grant VPN permission dialog → wait for LEVEL_CONNECTED
2. Tap "ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ" within ~2 s of connecting
3. Immediately tap "НАЧАТЬ ПОДКЛЮЧЕНИЕ" again (2nd attempt)
4. Tap "ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ" within ~2 s
5. Navigate to server list (tap server card) → return to MainActivity
   (triggers `VpnManager.syncStatus()` → `ACTION_SYNC_STATUS` → `stopSelf()` in ~1 s)
6. Tap "НАЧАТЬ ПОДКЛЮЧЕНИЕ" within ~1 s of returning (3rd attempt)
7. Observe: no crash, app stays on MainActivity

## Expected
- App remains on MainActivity after step 6
- Logcat shows `Service created` → `ACTION_START` on the 3rd attempt
- Zero `RemoteServiceException` in logcat
- Zero `FATAL EXCEPTION` in logcat

## Result (2026-06-25, <your-device-serial>)
PASS

## Evidence
```
21:44:10.678  I/OpenVPNGateApp:OpenVpnService: Service created
21:44:10.693  D/OpenVPNGateApp:OpenVpnService: ACTION_SYNC_STATUS
21:44:10.705  I/OpenVPNGateApp:OpenVpnService: Engine level=LEVEL_NOTCONNECTED detail=NOPROCESS source=AIDL
21:44:10.708  D/OpenVPNGateApp:OpenVpnService: One-shot initial state synced from AIDL snapshot
21:44:11.710  D/OpenVPNGateApp:OpenVpnService: One-shot status sync complete; stopping controller service
21:44:11.723  D/OpenVPNGateApp:OpenVpnService: Service destroyed and listener removed
RemoteServiceException count: 0
FATAL EXCEPTION count: 0
```
Key: `LEVEL_NOTCONNECTED` was received at 21:44:10.705 but `exitControllerForeground()` was
NOT called (fix working) — service destroyed cleanly 1018 ms later with no crash.
