# MQ-SUB04-003 — User disconnect does NOT enqueue a probe

## Preconditions
- APK installed and app launchable (MQ-SUB04-001 passed)
- ADB connected to Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)
- A valid VPN server is available and selectable in the country list

## Steps
1. From the main screen, select a country/server and tap Connect
2. Wait for LEVEL_CONNECTED — app shows "Connected" / "Подключено" state
3. Capture app PID if not already known:
   ```
   adb -s R58N849XQEY shell pidof com.yahorzabotsin.openvpnclientgate
   ```
4. Clear logcat to get a clean capture window:
   ```
   adb -s R58N849XQEY logcat -c
   ```
5. Tap the Disconnect button in the app (user-initiated disconnect — do NOT toggle WiFi or
   airplane mode)
6. Wait ~5 seconds for the disconnect to complete (app shows "Disconnected" / "Отключено")
7. Check that no probe was enqueued:
   ```
   adb -s R58N849XQEY logcat -d | findstr /i "ProbeRequestWorker\|enqueue hardprobe\|probe.*enqueue\|enqueue.*probe\|ProbeRequestQueue"
   ```
8. Also check WorkManager for any unexpected probe job:
   ```
   adb -s R58N849XQEY logcat -d | findstr /i "WorkManager\|ProbeRequestWorker"
   ```

## Expected
- Zero probe-enqueue log lines after the user-initiated disconnect
- `ProbeRequestWorker` does NOT appear in WorkManager logs for this disconnect event
- Normal disconnect log lines are present (e.g., `OpenVpnService`, LEVEL_NOTCONNECTED or similar)
- No `FATAL EXCEPTION` or Koin error lines

## Notes
- The distinction between user disconnect and autoswitch is guarded by the `isReconnect` /
  `extraAutoSwitchKey` flag in `OpenVpnService`. This test confirms that flag correctly suppresses
  probe enqueue on user stop.
- If `ProbeRequestWorker` lines appear, that is a defect: probe must only fire on server-failure
  autoswitch, not on user-initiated disconnect.

## Result
PASS

## Run date
2026-06-17

## Evidence
- Logcat cleared before tap, then `ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ` (center 540,2129) tapped
- Log captured:
  - `OpenVpnService: ACTION_STOP`
  - `stop_flow requestId=9cc733e9 session=1 source=user_action started=true`
  - CONNECTED → DISCONNECTING → DISCONNECTED
  - `Service destroyed and listener removed`
- Zero probe-related log entries (no `hardprobe`, `ProbeRequestWorker`, `enqueue`)
- AC-3 confirmed: user-initiated stop does not enqueue a probe
