# MQ-BUG-RRC-003 — Disconnect and reconnect stability (regression)

## AC
AC-5: No regression on normal connect / disconnect / reconnect

## Setup
1. VPN connected (run MQ-BUG-RRC-002 first)
2. Clear logcat: `adb -s R58N849XQEY shell logcat -c`

## Steps
1. Tap "ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ"
2. Wait for status to change to "Отключено" (~2 s)
3. Tap "НАЧАТЬ ПОДКЛЮЧЕНИЕ"
4. Wait up to 15 s

## Expected
- On disconnect: `App state: CONNECTED -> DISCONNECTING -> DISCONNECTED` in logcat
- On disconnect: `Service destroyed and listener removed` logged
- On reconnect: `ACTION_START` followed by `Engine level=LEVEL_CONNECTED`
- No `RemoteServiceException`

## Result (2026-06-25, R58N849XQEY)
PASS

## Evidence
```
# Disconnect
22:05:25.059  D/OpenVPNGateApp:VpnManager: stopVpn
22:05:25.075  I/OpenVPNGateApp:OpenVpnService: ACTION_STOP
22:05:25.108  I/OpenVPNGateApp:ConnectionState: App state: CONNECTED -> DISCONNECTING
22:05:25.260  I/OpenVPNGateApp:OpenVpnService: Engine level=LEVEL_NOTCONNECTED detail=NOPROCESS source=AIDL
22:05:25.274  I/OpenVPNGateApp:ConnectionState: App state: DISCONNECTING -> DISCONNECTED
22:05:25.351  D/OpenVPNGateApp:OpenVpnService: Service destroyed and listener removed

# Reconnect
22:06:52.230  I/OpenVPNGateApp:OpenVpnService: ACTION_START
22:06:52.260  I/OpenVPNGateApp:ConnectionState: App state: DISCONNECTED -> CONNECTING
22:06:55.891  I/OpenVPNGateApp:OpenVpnService: Engine level=LEVEL_CONNECTED detail=CONNECTED source=AIDL
```
Connect-to-connected latency: 3.661 s
