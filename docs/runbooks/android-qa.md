# Android QA Runbook

## SUB-01 — TS-8 Deferred Manual QA

**Story:** `docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-01-serverv2-model-id-and-ping.md`

TS-8 is the end-to-end ping display verification. It is blocked on the server team shipping `id` and
`ping` fields in the `DEFAULT_V2` API response (`VpnServerV2ListItemDto`). Until then:

- The server list will display `0 мс` for all V2 servers (expected behavior — same as before SUB-01)
- The `ServerV2.id` field will default to `0` for all servers

### When the server team ships `id` and `ping` (tracked in server repo `US-12-server-list-expose-id.md`)

1. Connect device: `adb devices` — ensure a real device is listed
2. Clear app data (optional, to avoid cache): `adb shell pm clear com.yahorzabotsin.openvpnclientgate`
3. Launch app: `adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
4. Select a V2-served country and wait for server list to load
5. Verify: at least one server shows non-zero ping (e.g., `37 мс`)
6. Logcat check: `adb logcat -d | grep -E "(fetchAllPages|ServersV2|JsonSyntax)"` — must show no `JsonSyntaxException`

### Devices tested for SUB-01 regression (AC-5, AC-7 cache backward-compat)

- Samsung Galaxy A71 SM_A715F, Android 13, ADB serial R58N849XQEY

---

## SUB-02 — WorkManager Probe Request Queue Manual QA

**Story:** `docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-02-workmanager-probe-request-queue.md`
**Device tested:** Samsung Galaxy A71 SM-A715F, Android 13, ADB serial R58N849XQEY

### ADB commands used for verification

```bash
# Install debug APK
adb -s R58N849XQEY install -r app-debug.apk

# Launch app via SplashActivity (required — direct MainActivity launch is known to fail; see Known Issues below)
adb -s R58N849XQEY shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity

# Verify WorkManager SystemJobService is active
adb -s R58N849XQEY shell dumpsys jobscheduler | grep -i openvpn

# Watch for ProbeRequestWorker activity
adb -s R58N849XQEY logcat -v time -s OpenVPNGateApp:ProbeRequestWorker

# Check for probe worker errors or unexpected crashes
adb -s R58N849XQEY logcat -d | grep -E "(ProbeRequestWorker|FATAL EXCEPTION|AndroidRuntime)"

# Verify ServersV2Repository still fetches correctly after named("v2") Retrofit refactor (regression)
adb -s R58N849XQEY logcat | grep -E "(ServersV2Repository|ServersV2SyncCoordinator|fetchAllPages)"
```

### Logcat tag for probe worker

`OpenVPNGateApp:ProbeRequestWorker`

No output under this tag is expected at this stage — the enqueue trigger is wired in SUB-04. Absence of the tag is the correct result; any `E`-level or `WTF` log line under this tag would indicate an unexpected initialization failure.

### What was verified (SUB-02)

| Check | Result |
|---|---|
| APK install (`adb install -r`) | PASS |
| App launch: SplashActivity → MainActivity, no crash | PASS |
| ServersV2Repository fetches from network (named("v2") regression) | PASS |
| WorkManager SystemJobService active | PASS |
| ServerRefreshWorker completing normally | PASS |
| No ProbeRequestWorker error logs | PASS (expected — trigger not yet wired) |
| MainActivitySmokeTest instrumented suite | SEE KNOWN ISSUES BELOW |

### Probe E2E verification — deferred to SUB-04

End-to-end probe dispatch (enqueue → worker runs → HTTP POST → response handling) cannot be
verified until SUB-04 wires the VPN inactivity trigger and the backend endpoint is reachable in
the test environment. When SUB-04 is delivered:

1. Trigger a VPN inactivity condition
2. Observe `ProbeRequestWorker` logcat output: expect `Result.success()` on HTTP 202
3. Verify no duplicate workers enqueued for the same `serverId` (KEEP policy)
4. Verify 429 responses produce retry backoff (check WorkManager job re-schedule in `dumpsys jobscheduler`)

### Known Issues

**`MainActivitySmokeTest` pre-existing failure (not caused by SUB-02)**

All 7 `MainActivitySmokeTest` cases fail with `NoActivityResumedException` on this device when
run via `./gradlew connectedDebugAndroidTestApp`. The same failure rate (7/7) is reproduced on
the `dev` branch with no SUB-02 changes applied, confirming this is not a regression introduced
by SUB-02.

Root cause: `ActivityScenario` launches `MainActivity` directly, bypassing `SplashActivity`.
`MainActivity` expects network calls (OkHttp) that are already in-flight from `SplashActivity`'s
preload phase; when those calls don't happen, the Espresso `IdlingResource` for OkHttp never
idles, causing the test to time out before the activity resumes. This is a test harness issue
unrelated to production code. See also `docs/runbooks/solutions.md` for the WorkManager test
isolation constraint.

---

## Logcat commands for SUB-01 regression validation

```bash
# Check for JSON parse errors
adb logcat -d | grep -E "(JsonSyntaxException|FATAL EXCEPTION)"

# Monitor server V2 loading
adb logcat | grep -E "(ServersV2Repository|SelectedCountryStore|ServersV2SyncCoordinator)"
```
