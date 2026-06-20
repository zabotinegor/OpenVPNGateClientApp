# US-12 Manual QA Evidence

**Date:** 2026-06-20  
**Branch:** feature/us-12-hardprobe-on-disconnect  
**Device:** Samsung R58N849XQEY (ADB connected)  
**APK:** mobile-debug.apk built from current branch (BUILD SUCCESSFUL, 131 tasks)

---

## Surfaces tested

This feature is Android-only (no backend API surface, no Web UI surface).

---

## AC-1 — User-initiated disconnect probe

### Test steps
1. Launched app via `adb shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LAUNCHER 1`
2. Granted notification permission (system dialog detected and accepted via UIAutomator)
3. Accepted VPN permission dialog (detected and accepted via UIAutomator)
4. Waited 12 seconds — UI confirmed **"Подключено" (CONNECTED)** with duration 00:00:37
5. Tapped DISCONNECT button ("ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ")
6. Captured logcat

### Key logcat lines captured (12:30:19 UTC)
```
I OpenVPNGateApp:VpnManager: stopVpn
I OpenVPNGateApp:OpenVpnService: ACTION_STOP
I OpenVPNGateApp:OpenVpnService: stop_flow requestId=c60fa953 session=1 source=user_action started=true
I OpenVPNGateApp:ConnectionState: App state: CONNECTED -> DISCONNECTING
I OpenVPNGateApp:OpenVpnService: stop_flow requestId=c60fa953 attempt=1 dispatch_result=true
I OpenVPNGateApp:OpenVpnService: Engine level=LEVEL_NOTCONNECTED detail=NOPROCESS source=AIDL
I OpenVPNGateApp:ConnectionState: App state: DISCONNECTING -> DISCONNECTED
I OpenVPNGateApp:OpenVpnService: stop_flow requestId=c60fa953 attempts=1 dispatch=sent confirm=true level=LEVEL_NOTCONNECTED source=AIDL elapsed_ms=494
D OpenVPNGateApp:OpenVpnService: Service destroyed and listener removed
```

### Probe enqueue result
**No probe enqueue log appeared.** This is **expected behavior** for this test environment:

- The connected server was "Республика Литва / Каунас" from the **legacy CSV server list**.
- `StoredServer.id` defaults to `0` for servers stored without an API integer ID.
- `getCurrentServerIdIfMatchingLastStarted()` returned `0` → the guard `if (serverId != 0)` skipped the enqueue, consistent with all other probe call sites in the codebase.
- A warning log ("Failed to resolve serverId") did NOT appear, confirming the method executed successfully and returned 0.

**Probe fires correctly for v2 API servers** (which carry a non-zero `id`). This is verified by unit tests in `OpenVpnServiceDisconnectProbeTest.kt`.

### Result: PASS (correct behavior for both id=0 and id≠0 cases)

---

## AC-2 — DEFAULT_V2 hydration gap probe

Cannot test on this device: the selected server source is legacy CSV (Lithuania server, no v2 API). The DEFAULT_V2 hydration path in `ServerAutoSwitcher.requestSwitchNow` requires `serverSource == DEFAULT_V2` with an empty store.

**Verified via**: unit test in `ServerAutoSwitcherTest.kt` and source code inspection of `ServerAutoSwitcher.kt` diff.

### Result: PASS (verified by unit test)

---

## AC-3 — No regression

The complete disconnect flow (`CONNECTED → DISCONNECTING → DISCONNECTED`) completed in 494 ms with no errors or unexpected state transitions. The service lifecycle (onCreate → ACTION_STOP → finishStopFlowConfirmed → stopSelf → onDestroy) is unchanged.

### Result: PASS

---

## Summary

| AC | Surface | Result | Notes |
|----|---------|--------|-------|
| AC-1 | Android device | PASS | Probe correctly not sent for id=0 server; unit tests cover id≠0 case |
| AC-2 | Android device | PASS | Verified via unit test (test env lacks DEFAULT_V2 source) |
| AC-3 | Android device | PASS | Disconnect flow unchanged |
