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

**`MainActivitySmokeTest` failure (not caused by SUB-02) — RESOLVED in SUB-05**

All 7 `MainActivitySmokeTest` cases failed with `NoActivityResumedException` on this device when
run via `./gradlew connectedDebugAndroidTestApp`. The same failure rate (7/7) was reproduced on
the `dev` branch with no SUB-02 changes applied, confirming it was not a regression from SUB-02.

This issue was fixed in SUB-05. The actual root cause was `FLAG_ACTIVITY_NEW_TASK |
FLAG_ACTIVITY_CLEAR_TASK` flags on the `ActivityScenario` launch intents conflicting with
Espresso's lifecycle management. All 21 tests now pass on Samsung Galaxy A71 SM-A715F Android 13.
See `docs/runbooks/solutions.md` for full details.

---

---

## MP-20260621 SUB-02 — Android SSE Client for Server-Push Notifications

**Story:** `docs/userstories/MP-20260621-server-push-sse/SUB-02-android-sse-client.md`
**Device tested:** Samsung Galaxy A71 SM-A715F, Android 13, ADB serial R58N849XQEY
**Backend endpoint:** `https://openvpngateclientgate.azurewebsites.net/api/v1/servers/events`

### Logcat tags

| Tag | What it covers |
|---|---|
| `OpenVPNGateApp:SseServerEventsClient` | SSE connection open/close/failure, backoff retries, servers-changed events |
| `OpenVPNGateApp:CoreApp` | SSE lifecycle observer registration on app start |

### ADB commands for SSE QA verification

```bash
# Stream SSE-related logcat (connection lifecycle + event receipt)
adb -s R58N849XQEY logcat -v time -s "OpenVPNGateApp:SseServerEventsClient"

# Also show CoreApp registration line at startup
adb -s R58N849XQEY logcat -v time -e "SseServerEventsClient|CoreApp"

# Confirm SSE connection opened (look for HTTP 200 and "SSE connection opened" log line)
adb -s R58N849XQEY logcat -d | grep -E "SSE connection (opened|closed|failure)"

# Verify a servers-changed event triggered a sync (look for "servers-changed event received; triggering server re-fetch")
adb -s R58N849XQEY logcat -d | grep "servers-changed"

# Monitor the downstream sync that fires on SSE event
adb -s R58N849XQEY logcat | grep -E "(ServersV2Repository|ServersV2SyncCoordinator|fetchAllPages)"

# Verify SSE client starts on foreground and stops on background
adb -s R58N849XQEY logcat -d | grep -E "SSE client (starting|stopping)"

# Check for SSE backoff retries (exponential delay log lines)
adb -s R58N849XQEY logcat -d | grep "SSE reconnect in"

# Check for any fatal errors during SSE lifecycle registration
adb -s R58N849XQEY logcat -d | grep -E "(FATAL EXCEPTION|Failed to register SSE)"
```

### Manual QA steps for SSE

1. Install debug APK: `adb -s R58N849XQEY install -r app-debug.apk`
2. Start logcat in a separate terminal: `adb -s R58N849XQEY logcat -v time -e "SseServerEventsClient|CoreApp"`
3. Launch app: `adb -s R58N849XQEY shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
4. Verify logcat shows "SSE lifecycle observer registered" (CoreApp) then "SSE client starting" and "SSE connection opened (HTTP 200)" (SseServerEventsClient)
5. Background the app (press Home)
6. Verify logcat shows "SSE client stopping" and "SSE connection closed"
7. Foreground the app again; verify "SSE client starting" and "SSE connection opened" repeat
8. When the backend pushes a `servers-changed` event, verify "servers-changed event received; triggering server re-fetch" appears, followed by `ServersV2SyncCoordinator` fetch logs

---

## Logcat commands for SUB-01 regression validation

```bash
# Check for JSON parse errors
adb logcat -d | grep -E "(JsonSyntaxException|FATAL EXCEPTION)"

# Monitor server V2 loading
adb logcat | grep -E "(ServersV2Repository|SelectedCountryStore|ServersV2SyncCoordinator)"
```

---

## SUB-05 — Instrumented test fixes

**Story:** `docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-05-fix-broken-instrumented-tests.md`

### What was fixed

1. **Test launch flags**: Removed `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` from
   `ActivityScenario.launch()` intents (test code only — no production code changed). These flags
   conflict with Espresso's lifecycle management and prevent the activity from reaching RESUMED state.

2. **Update dialog dismissal**: Added `dismissUpdatePromptIfVisible()` in test helpers to handle
   the async `PromptUpdate` effect that shows an update dialog on launch.

### Known Samsung device limitation

On Samsung SM-A715F (Android 13), `MainActivity` tests were previously reported to fail with
`NoActivityResumedException` due to Samsung's Freecess/GameSDK pausing the activity after RESUMED.

**Updated (SUB-05):** The Samsung whitelist workaround has been confirmed to work. After running:

```bash
adb shell cmd deviceidle whitelist +com.yahorzabotsin.openvpnclientgate
```

and applying the SUB-05 fix (removing `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` from
launch intents and adding `dismissUpdatePromptIfVisible()`), all 21 tests pass on Samsung Galaxy
A71 SM-A715F Android 13 (ADB serial R58N849XQEY) — 0 failures.

`SettingsActivity`, `DnsActivity`, and `ConnectionControlsView` tests continue to pass normally.

### Mi 9T Pro (MIUI / Android 11) limitation

On Xiaomi Mi 9T Pro (ADB serial b6e8f6bd, Android 11 / MIUI), instrumented tests are blocked
indefinitely by the Android 11 background activity start restriction. `ActivityScenario.launch()`
cannot launch activities when called from the instrumentation runner under MIUI's enforcement of
this restriction.

**Symptoms:**
- Tests do not fail with an error — they simply never start; the process hangs with no output.
- No timeout fires; the ADB process must be killed manually.

**What does not help:**
- `adb shell cmd deviceidle whitelist +com.yahorzabotsin.openvpnclientgate` — MIUI ignores the
  standard idle whitelist for this restriction.

**Workaround:** Use a Samsung or stock Android device for instrumented test runs. The Samsung
Galaxy A71 (Android 13) is the confirmed working test device for this project.

### Running the tests

```bash
# Run all instrumented tests
adb shell am instrument -w com.yahorzabotsin.openvpnclientgate.test/androidx.test.runner.AndroidJUnitRunner

# Run a specific test class
adb shell am instrument -w -e class com.yahorzabotsin.openvpnclientgate.mobile.MainActivitySmokeTest com.yahorzabotsin.openvpnclientgate.test/androidx.test.runner.AndroidJUnitRunner

# Run via Gradle (handles APK build + install)
./gradlew connectedDebugAndroidTestApp
```
