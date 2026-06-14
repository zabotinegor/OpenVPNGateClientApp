# MQ-SUB01-004 — VPN connect/disconnect cycle completes without crash

## Preconditions
- App in MainActivity, country "Республика Литва" (LT) selected, server 2/2
- Network connectivity available
- ADB connected

## Steps
1. Clear logcat: `adb logcat -c`
2. Tap connect button (НАЧАТЬ ПОДКЛЮЧЕНИЕ)
3. Wait for `LEVEL_CONNECTED` in logcat
4. Wait for `trafficDelta > 0` (active traffic confirmed)
5. Tap disconnect (or use `adb shell am broadcast -a de.blinkt.openvpn.DISCONNECT_VPN`)
6. Wait for `ConnectionState: DISCONNECTING -> DISCONNECTED`
7. Check logcat for FATAL/AndroidRuntime/exception

## Expected
- Full state sequence: DISCONNECTED → CONNECTING → CONNECTED → DISCONNECTING → DISCONNECTED
- trafficDelta > 0 while CONNECTED
- Zero fatal exceptions throughout

## Result: PASS
- logcat: `ConnectionState: App state: DISCONNECTED -> CONNECTING` (19:38:30.417)
- logcat: `OpenVpnService: Engine level=LEVEL_CONNECTED detail=CONNECTED source=AIDL` (19:38:33.605)
- logcat: `Watchdog: healthy source=traffic trafficDelta=4408` (19:38:34.481) — active traffic
- logcat: `OpenVpnService: ACTION_STOP` (19:41:45.905)
- logcat: `ConnectionState: App state: CONNECTED -> DISCONNECTING` (19:41:45.927)
- logcat: `ConnectionState: App state: DISCONNECTING -> DISCONNECTED` (19:41:46.069)
- logcat grep FATAL/AndroidRuntime on pid=20642: (empty — zero results)
- Final screenshot: app back in disconnected state, server 2/2, Lithuania, clean UI

## Evidence
- logcat timestamps: connect=19:38:30, LEVEL_CONNECTED=19:38:33, disconnect=19:41:46
- Connection duration: ~3 minutes 16 seconds
- `stop_flow requestId=f052edf5 attempts=1 dispatch=sent confirm=true level=LEVEL_NOTCONNECTED elapsed_ms=165`

## Run date
2026-06-14
