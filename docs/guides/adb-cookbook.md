# Android QA ADB Cookbook

Reusable ADB one-liners for manual QA on real devices. Replace `<SERIAL>` with the device serial from `adb devices`.

---

## Device Control

```powershell
# Keep screen on (10 minutes)
adb -s <SERIAL> shell settings put system screen_off_timeout 600000

# Wake a locked device
adb -s <SERIAL> shell input keyevent KEYCODE_WAKEUP

# Swipe to unlock (adjust coordinates for device resolution)
adb -s <SERIAL> shell input swipe 540 1500 540 800
```

---

## App Lifecycle

```powershell
# Force stop the app
adb -s <SERIAL> shell am force-stop com.yahorzabotsin.openvpnclientgate

# Launch the app (LAUNCHER intent)
adb -s <SERIAL> shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LAUNCHER 1
```

---

## SharedPreferences Inspection and Modification

All prefs files live under `/data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/` and require `run-as` to access.

```powershell
# List all prefs files on device
adb -s <SERIAL> shell "run-as com.yahorzabotsin.openvpnclientgate find /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/ -name '*.xml'"

# Read a specific prefs file
adb -s <SERIAL> shell "run-as com.yahorzabotsin.openvpnclientgate cat /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/<file>.xml"

# Check selected server IDs (DEFAULT_V2 source)
# Read vpn_selection_prefs.xml, look at key "selected_country_servers"
# Value is a JSON array; each object has an "id" field (int, must be non-zero for probe enqueue)
adb -s <SERIAL> shell "run-as com.yahorzabotsin.openvpnclientgate cat /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/vpn_selection_prefs.xml"
```

### Modifying SharedPreferences for QA tuning

Push a correctly-formatted XML file to a temp location, then copy it into the app's prefs directory using `run-as`. The app must be stopped before overwriting prefs files.

```powershell
# 1. Push the XML to a world-readable temp location
adb -s <SERIAL> push <local-file>.xml /data/local/tmp/<file>.xml

# 2. Copy into the prefs directory as the app user
adb -s <SERIAL> shell "run-as com.yahorzabotsin.openvpnclientgate cp /data/local/tmp/<file>.xml /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/<file>.xml"
```

#### Tuning the stall timeout (`user_settings.xml`)

- Prefs name: `user_settings`
- Key: `status_stall_timeout_seconds` (int)
- Default: `UserSettingsStore.DEFAULT_STATUS_STALL_TIMEOUT_SECONDS = 5`

The `noReplyThresholdSeconds` equals this value; `repliedThresholdSeconds = noReplyThresholdSeconds + REPLIED_TIMEOUT_EXTRA_SECONDS` (3 s extra). Reducing the timeout to 1 s gives `noReply=1 s`, `replied=4 s`.

> **Note:** With fast-connecting servers (TCP_CONNECT to LEVEL_CONNECTED in ~2-3 s), reducing the timeout alone may not be sufficient to trigger an autoswitch because the server replies during the threshold window. Use a server that delays AUTH/ASSIGN_IP to reliably trigger stall detection.

---

## Probe and Autoswitch Verification

```powershell
# Filter logcat for autoswitch and probe events
adb -s <SERIAL> logcat -d | Select-String "LEVEL_|probe|Switcher|AutoSwitch|enqueue|hardprobe"

# Filter for DI wiring errors (probe queue not injected)
adb -s <SERIAL> logcat -d | Select-String "Failed to wire ProbeRequestQueue|NoBeanDefFoundException|KoinException"
```

### What to look for

| Log signal | Meaning |
|---|---|
| `LEVEL_NONETWORK` | Device lost connectivity; `failingServerId` is forced to 0 (no probe enqueued) |
| `enqueue` with a non-zero server ID | Probe request dispatched for that server |
| `NoBeanDefFoundException` / `KoinException` | `ProbeRequestQueue` not wired in Koin DI; check `CoreDi.kt` |

### Probe trigger points

See `src/docs/server-sync-flow.md`'s "Hardprobe Trigger Points" section for the canonical, current
list of code paths that enqueue a probe — this cookbook used to keep its own copy of that list and
it had drifted stale (undercounting the paths). Server IDs must be non-zero for a probe to be
enqueued; a zero ID is silently skipped.

---

## SSE client verification

`SseServerEventsClient` logs under the tag `OpenVPNGateApp:SseServerEventsClient`.

```powershell
# Confirm SSE client started and how many candidate URLs are configured
adb -s <SERIAL> logcat -d | Select-String "SseServerEventsClient"

# Key log lines
# "SSE client starting; 1 candidate url(s)"   → normal; default production config has 1 URL (PRIMARY only)
# "SSE client starting; N candidate url(s)"   → test/custom injection with N URLs
# "SSE connection opened (HTTP 200)"           → successful connection; failure counter reset
# "SSE URL exhausted after 3 failure(s); switching to <url>"  → URL rotation triggered (custom multi-URL)
```

### What to look for

| Log signal | Meaning |
|---|---|
| `SSE client starting; 1 candidate url(s)` | Normal production start — only `PRIMARY_SERVERS_URL` is an SSE-capable endpoint |
| `SSE connection opened (HTTP 200)` | Connection succeeded; `failuresOnCurrentUrl` reset to 0 |
| `SSE URL exhausted after N failure(s); switching to <url>` | `urlFailureThreshold` (default 3) consecutive failures; rotating to next URL |
| `SSE reconnect in Xms (attempt=N)` | Exponential backoff between reconnect attempts |
| `servers-changed event received; triggering server re-fetch` | Backend push received; sync will fire after 500 ms debounce |
