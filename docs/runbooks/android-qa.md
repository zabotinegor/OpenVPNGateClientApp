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

## Logcat commands for SUB-01 regression validation

```bash
# Check for JSON parse errors
adb logcat -d | grep -E "(JsonSyntaxException|FATAL EXCEPTION)"

# Monitor server V2 loading
adb logcat | grep -E "(ServersV2Repository|SelectedCountryStore|ServersV2SyncCoordinator)"
```
