# MQ-BUG-CRASH-004 — ServerAutoSwitcher no regression

## AC
AC-4: No regression on server switch (auto-switch via ServerAutoSwitcher)

## Setup
VPN connected and watchdog running

## Steps
1. During VPN connection observe logcat for ServerAutoSwitcher activity
2. Verify timeout timers start and stop at each connection phase
3. Verify watchdog "healthy" markers with positive trafficDelta

## Expected
- ServerAutoSwitcher timeout timers track each connection phase
- No spurious requestSwitchNow during stable connection
- Watchdog reports healthy with trafficDelta > 0

## Result (2026-06-18, R58N849XQEY)
PASS

## Evidence
```
18:19:16.315  ServerAutoSwitcher: Engine level received: level=LEVEL_CONNECTING_NO_SERVER_REPLY_YET
18:19:16.319  ServerAutoSwitcher: Timeout timer started for level=LEVEL_CONNECTING_NO_SERVER_REPLY_YET
18:19:16.416  ServerAutoSwitcher: Auto-switch timer level change: LEVEL_CONNECTING_NO_SERVER_REPLY_YET -> LEVEL_CONNECTING_SERVER_REPLIED
18:19:16.419  ServerAutoSwitcher: Timeout timer started for level=LEVEL_CONNECTING_SERVER_REPLIED
18:19:18.048  ServerAutoSwitcher: Engine level received: level=LEVEL_CONNECTED
18:19:18.052  ServerAutoSwitcher: Switch timer stopped at 1s
18:19:23.115  Watchdog: healthy source=traffic trafficDelta=22985
18:19:29.345  Watchdog: healthy source=probe trafficDelta=140
No requestSwitchNow triggered during stable connection
```
