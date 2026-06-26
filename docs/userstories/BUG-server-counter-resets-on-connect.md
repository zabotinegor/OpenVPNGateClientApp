---
title: "BUG-FIX: Server counter resets from N/N to 1/N immediately when user taps Connect"
description: |
  User selects the Nth server in a country, sees N/N in the counter, taps Connect,
  and the counter immediately snaps to 1/N before the VPN starts.

## Context
- Reported: 2026-06-25
- Affected build: 100
- Country tested: Belarus (3 servers, all sharing IP 213.184.224.127)
- Evidence: logcat_20260625_155649.txt (local capture, not committed)
- Source files:
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainSelectionInteractor.kt`

## Reproduction Steps
1. Select a country with ≥ 2 servers where multiple servers share the same IP
   (e.g. Belarus — 3 servers, all IP `213.184.224.127`).
2. Select the **last** server (e.g. server 3 of 3) → counter shows **3/3**.
3. Return to main screen (if not already there).
4. Tap **Connect**.
5. **Before** the VPN connection begins, counter immediately changes to **1/3** (or **1/N**).

## Expected Behavior
Counter stays at **3/3** (the server the user explicitly picked) throughout the connect sequence.

## Actual Behavior
Counter drops to **1/3** the moment Connect is tapped, before the VPN starts.

---

## Root Cause — Two cooperating defects

### Defect A (primary — always triggers): `loadInitialSelection` clears `pendingUserSelectionOverride`

`MainViewModel.loadInitialSelection()` runs as a coroutine launched from `onCreate()`. When it
completes it unconditionally calls:

```kotlin
// MainViewModel.kt line 99
updateSelectedServer(
    ...
    fromUserSelection = false   // ← sets pendingUserSelectionOverride = false
)
```

If this coroutine finishes **after** `onServerSelectionResult()` sets
`pendingUserSelectionOverride = true` (i.e. the user selected a server while the startup load
was still in flight), it silently resets the flag to `false`.

From the logcat: `onServerSelectionResult` fires at T=15:55:21.554; the `loadInitialSelection`
coroutine completes at T=15:55:21.683 — 129 ms later — and overwrites the flag.

When the user taps Connect 1 second later, `prepareStart(preferUserSelection = false)` is called.
Because `preferUserSelection = false` and `lastSuccessfulConfig` is set from a previous Belarus
session (server 1), `shouldUseLastSuccessful = true` → the old server-1 config is used instead
of the user's server-3 selection. The store index is then set to 0 (server 1), and the counter
displays **1/3**.

`syncServersForForegroundIfDue()` already has the correct guard (line 135), but
`loadInitialSelection()` does not.

### Defect B (defense-in-depth): `hydrateStoredSelectionFromV2` uses OR-priority search

`hydrateStoredSelectionFromV2()` is called whenever the stored city is blank (always true for
V2 servers). It searches for the user's server using OR logic:

```kotlin
// MainSelectionInteractor.kt lines 168-171
val selectedIndex = legacyServers.indexOfFirst { srv ->
    (!selectedIp.isNullOrBlank() && srv.ip == selectedIp) ||   // IP checked first
        (!selectedConfig.isNullOrBlank() && srv.configData == selectedConfig)
}.takeIf { it >= 0 } ?: 0
```

When multiple servers share the same IP (common in OpenVPN Gate, confirmed for Belarus),
`indexOfFirst` always returns index 0 (the first server matching the IP) — never the user's
server 3 — because the IP branch is evaluated first and `indexOfFirst` stops at the first match.

`configData` is unique per server and is the correct primary key. IP is a fallback.

---

## Fix Approach

### File 1: `MainViewModel.kt` (line 97)

Add a `pendingUserSelectionOverride` guard before the `updateSelectedServer` call in
`loadInitialSelection()`, mirroring the pattern already used in `syncServersForForegroundIfDue()`:

```kotlin
val selection = selectionInteractor.loadInitialSelection(cacheOnly = cacheOnly) ?: return@launch
logger.logInitialSelectionLoaded(selection)
if (_state.value.pendingUserSelectionOverride) return@launch   // ← ADD THIS
updateSelectedServer(
    country = selection.country,
    countryCode = selection.countryCode,
    city = selection.city,
    config = selection.config,
    ip = selection.ip,
    fromUserSelection = false
)
```

### File 2: `MainSelectionInteractor.kt` (lines 168-172)

Change the search to sequential: config first (exact match, unique per server), then IP as fallback:

```kotlin
val selectedIndex = when {
    !selectedConfig.isNullOrBlank() ->
        legacyServers.indexOfFirst { it.configData == selectedConfig }
            .takeIf { it >= 0 }
            ?: legacyServers.indexOfFirst { !selectedIp.isNullOrBlank() && it.ip == selectedIp }
                .takeIf { it >= 0 }
            ?: 0
    !selectedIp.isNullOrBlank() ->
        legacyServers.indexOfFirst { it.ip == selectedIp }.takeIf { it >= 0 } ?: 0
    else -> 0
}
```

---

## Regression Risk Areas
1. **`loadInitialSelection` fresh-start path** — if the user has NOT yet made a manual selection,
   `pendingUserSelectionOverride = false` → guard doesn't fire → behavior unchanged.
2. **`loadInitialSelection` race** — if the coroutine completes while user is selecting a server
   (override = true), we now skip the update instead of overwriting. On the next
   `SelectedCountryVersionSignal` bump or foreground sync, the UI naturally syncs from cache.
3. **`hydrateStoredSelectionFromV2` — fresh install / no cached selection** — `selectedConfig` is
   null/blank only when there is no prior config stored. The `else -> 0` fallback is unchanged.
4. **`hydrateStoredSelectionFromV2` — country with unique IPs** — config-first search still
   finds the correct server; IP fallback is no-op. No behavior change.
5. **`onStoreVersionChanged` / `syncServersForForegroundIfDue`** — both already have the
   `pendingUserSelectionOverride` guard; no change needed there.
6. **`MainConnectionInteractor.prepareStart`** — `preferUserSelection = pendingUserSelectionOverride`
   at call time; once Defect A is fixed, the flag correctly reflects the user's state.

## Acceptance Criteria
- [ ] Select server 3/3 in a country with ≥ 2 servers → tap Connect → counter stays at **3/3**
      before connection starts
- [ ] The `CountryServersInteractor` log `chosenIndex=3/3` matches the service log `server=3/3`
      at session start
- [ ] No regression: fresh launch (no prior selection) auto-selects server 1, counter shows **1/N**
- [ ] No regression: background sync does not overwrite a user's explicit selection
- [ ] No regression: `ServerAutoSwitcher` reconnect still picks the last-successful server
- [ ] No regression: second connect after a successful connect still uses last-successful server
      when user has not re-selected

## Implementation Handoff
- Branch: `fix/server-counter-resets-on-connect`
- Story path: `docs/userstories/BUG-server-counter-resets-on-connect.md`
- Files to change:
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt`
    - After line 98 (`logger.logInitialSelectionLoaded(selection)`): add
      `if (_state.value.pendingUserSelectionOverride) return@launch`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainSelectionInteractor.kt`
    - Lines 168-171: replace OR-logic `indexOfFirst` with sequential config-first, IP-fallback search
