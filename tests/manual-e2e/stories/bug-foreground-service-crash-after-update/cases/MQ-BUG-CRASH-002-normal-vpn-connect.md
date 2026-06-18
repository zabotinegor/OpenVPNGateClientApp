# MQ-BUG-CRASH-002 — Normal VPN connect (no update scenario)

## AC
AC-2: No regression on normal VPN connect (no update scenario)

## Setup
App installed and at MainActivity (server preload complete)

## Steps
1. Clear logcat
2. Tap Connect button ("НАЧАТЬ ПОДКЛЮЧЕНИЕ") — approve notification + VPN permissions if first run
3. Wait 20s for connection

## Expected
- ACTION_START logged
- VPN_GENERATE_CONFIG -> TCP_CONNECT -> AUTH -> GET_CONFIG -> ASSIGN_IP -> LEVEL_CONNECTED sequence
- Zero RemoteServiceException, zero FATAL EXCEPTION
- UI shows "Подключено" (Connected) with traffic bytes > 0

## Result (2026-06-18, R58N849XQEY)
PASS

## Evidence
```
18:17:30.105  Service created
18:17:30.122  ACTION_START
18:17:30.185  Engine level=LEVEL_START detail=VPN_GENERATE_CONFIG
18:17:31.385  Engine level=LEVEL_CONNECTING_NO_SERVER_REPLY_YET detail=TCP_CONNECT
18:17:31.520  Engine level=LEVEL_CONNECTING_SERVER_REPLIED detail=AUTH
18:17:32.871  Engine level=LEVEL_CONNECTING_SERVER_REPLIED detail=GET_CONFIG
18:17:32.909  Engine level=LEVEL_CONNECTING_SERVER_REPLIED detail=ASSIGN_IP
18:17:33.194  Engine level=LEVEL_CONNECTED detail=CONNECTED
18:17:38.253  Watchdog: healthy source=traffic trafficDelta=32935
RemoteServiceException count: 0
FATAL EXCEPTION count: 0
UI: Status=Подключено, Downloaded=39.39 KB, Uploaded=23.20 KB
```
