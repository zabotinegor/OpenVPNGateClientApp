# SMOKE-03 — VPN connect + watchdog + disconnect

**Result: PASS**

## Evidence

Logcat (pid 24603) — second connect session:

```
08:11:07.321  I  ConnectionState: App state: DISCONNECTED -> CONNECTING
08:11:07.328  I  OpenVpnService: Requested engine start (profile=Австралия)
08:11:07.359  D  OpenVpnService: Engine state (AIDL): level=LEVEL_START state=VPN_GENERATE_CONFIG
08:11:13.388  D  OpenVpnService: Engine state (AIDL): level=LEVEL_CONNECTED state=CONNECTED
08:11:13.409  I  ConnectionState: App state: CONNECTING -> CONNECTED
08:11:13.416  I  OpenVpnService: Watchdog: healthy source=traffic trafficDelta=617
```

Disconnect (user-initiated via ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ button):

```
08:31:24.822  I  OpenVpnService: stop_flow requestId=5026c00c session=1 source=user_action
08:31:24.838  I  ConnectionState: App state: CONNECTED -> DISCONNECTING
08:31:26.444  I  ConnectionState: App state: DISCONNECTING -> DISCONNECTED
08:31:26.449  I  OpenVpnService: stop_flow ... elapsed_ms=1634
```

UI screenshot at CONNECTED state (12-current-state.png):
- СТАТУС: Подключено
- ДЛИТЕЛЬНОСТЬ: 00:18:08
- Downloaded: 8.87 MB / Uploaded: 1.68 MB
- ПАУЗА + ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ buttons visible

## Notes

VPN_GENERATE_CONFIG state was emitted (engine SWIG generation path in new API executed without error).
Connect time: ~6 s from CONNECTING to CONNECTED.
Disconnect time: 1.6 s.
