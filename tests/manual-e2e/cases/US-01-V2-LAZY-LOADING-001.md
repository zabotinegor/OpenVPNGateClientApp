---
id: US-01-V2-LAZY-LOADING-001
title: US-01 v2 lazy-loading regression and acceptance case
area: Server Source and Country/Server Loading
surface: android
---

## Preconditions
- Fresh debug install is present on device.
- Device has internet connectivity.
- DEFAULT_V2 source is available in settings.

## Steps
1. Open country picker screen and verify countries are listed with flags and counts.
2. Select a country (for example, Australia) and open server list.
3. Confirm server list screen loads and at least one server is displayed.
4. Select the server and verify selected server appears on main screen.
5. Disconnect if connected and verify disconnected state is reachable.
6. From server list screen, press Back and verify return to country list.
7. Re-enter the same country and verify server list loads again (cache-hit behavior).
8. Open Settings and verify source item is set to `Client for OpenVPN Gate` (DEFAULT_V2).

## Assertions
- No `JSONException` is present in logcat during country/server loading.
- Country list and server list are both reachable from UI.
- Server selection is applied to main screen.
- Back navigation from server list returns to country list.
- DEFAULT_V2 remains selected in settings.

## Evidence Required
- Screenshots: ME-3, ME-4, ME-8, ME-10, ME-11, ME-12.
- Log excerpt: clean result for app parsing path after ME-4.
- Artifact folder: [../../../artifacts/manual-qa/2026-05-06-us01-v2-lazy-loading](../../../artifacts/manual-qa/2026-05-06-us01-v2-lazy-loading)
