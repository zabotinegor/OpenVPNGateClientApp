# MQ-BUG-CRASH-003 — VPN disconnect and reconnect

## AC
AC-3: No regression on VPN disconnect and reconnect

## Setup
VPN connected (from MQ-BUG-CRASH-002)

## Steps
1. Clear logcat
2. Tap "ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ" (Stop VPN)
3. Wait for disconnect
4. Tap "НАЧАТЬ ПОДКЛЮЧЕНИЕ" (Connect) again
5. Wait 20s for reconnection

## Expected
Disconnect:
- ACTION_STOP logged
- stop_flow dispatch_result=true
- LEVEL_NOTCONNECTED confirmed
- Service destroyed and listener removed

Reconnect:
- ACTION_START logged
- LEVEL_CONNECTED reached
- Zero RemoteServiceException, zero FATAL EXCEPTION

## Result (2026-06-18, R58N849XQEY)
PASS

## Evidence
Disconnect:
```
18:18:31.017  ACTION_STOP
18:18:31.023  stop_flow requestId=b47b21fa source=user_action started=true
18:18:31.055  stop_flow requestId=b47b21fa attempt=1 dispatch_result=true
18:18:31.279  stop_flow requestId=b47b21fa confirm=true level=LEVEL_NOTCONNECTED elapsed_ms=261
18:18:31.303  Service destroyed and listener removed
```
Reconnect:
```
18:19:14.996  Service created
18:19:15.012  ACTION_START
18:19:18.039  Engine level=LEVEL_CONNECTED detail=CONNECTED
18:19:23.115  Watchdog: healthy source=traffic trafficDelta=22985
RemoteServiceException count: 0
FATAL EXCEPTION count: 0
```
