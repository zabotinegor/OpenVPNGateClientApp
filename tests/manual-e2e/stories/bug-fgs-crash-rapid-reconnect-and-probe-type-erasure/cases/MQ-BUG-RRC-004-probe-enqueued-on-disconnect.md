# MQ-BUG-RRC-004 — Probe enqueued and succeeds on VPN disconnect

## AC
AC-3: `ProbeRequestWorker` no longer throws `IllegalArgumentException` for type erasure
AC-4: No logcat `W/ProbeRequestWorker` for "Response must include generic type"
AC-6: Probe is enqueued and succeeds on VPN disconnect

## Setup
1. VPN connected
2. Clear logcat: `adb -s <your-device-serial> shell logcat -c`

## Steps
1. Tap "ОСТАНОВИТЬ ПОДКЛЮЧЕНИЕ" to disconnect VPN
2. Wait 3 s
3. Check logcat for probe enqueue and result

## Expected
- `WorkManagerProbeRequestQueue: Probe enqueued: serverId=<n>` in logcat within 1 s of disconnect
- `WM-WorkerWrapper: Starting work for ...ProbeRequestWorker` in logcat
- `ProbeRequestWorker: Probe succeeded: serverId=<n>, status=2xx` in logcat
- `WM-WorkerWrapper: Worker result SUCCESS` in logcat
- Zero `IllegalArgumentException` mentioning "Response must include generic type"

## Result (2026-06-25, <your-device-serial>)
PASS

## Evidence
```
22:05:25.292  D/OpenVPNGateApp:WorkManagerProbeRequestQueue: Probe enqueued: serverId=8711, uniqueName=probe-server-8711
22:05:25.446  D/WM-WorkerWrapper: Starting work for ...ProbeRequestWorker
22:05:26.623  I/OpenVPNGateApp:ProbeRequestWorker: Probe succeeded: serverId=8711, status=202
22:05:26.623  I/WM-WorkerWrapper: Worker result SUCCESS for Work [ id=285f16e5-8ef1-4978-be5f-f67bc16266bb, tags={...ProbeRequestWorker,server-probe} ]
```
Probe latency from enqueue to success: 1.331 s
IllegalArgumentException count: 0
"Response must include generic type" count: 0
