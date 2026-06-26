# Solutions Runbook

Recurring bug patterns and their root causes, for fast diagnosis of future regressions.

---

## Server counter resets to position 1 immediately after the user picks a server

**Branch fixed on:** `fix/server-counter-resets-on-connect`  
**Files changed:** `MainViewModel.kt`, `MainSelectionInteractor.kt`

### Symptoms

- User opens the server list, taps a specific server (e.g. server 3/10).
- The main screen flashes the correct selection, then immediately reverts to a different server (typically server 1/N or a server that shares an IP with the intended one).
- The counter shown in the details line (`Server: X/N`) does not reflect the user's choice by the time they tap Connect.

### Root causes (two independent bugs, both must be fixed)

**Bug 1 — startup coroutine race in `MainViewModel.loadInitialSelection()`**

`loadInitialSelection()` runs in a coroutine launched during `MainViewModel` init. If the user selects a server while this coroutine is still in-flight, the coroutine's final `updateSelectedServer(...)` call would overwrite the user's explicit selection because it executed after `pendingUserSelectionOverride` was set to `true` but the check was absent.

Fix: after the coroutine receives the result of `selectionInteractor.loadInitialSelection(...)`, check `_state.value.pendingUserSelectionOverride` before calling `updateSelectedServer(...)`. If the flag is `true`, return early — the user's selection wins.

```kotlin
val selection = selectionInteractor.loadInitialSelection(cacheOnly = cacheOnly) ?: return@launch
if (_state.value.pendingUserSelectionOverride) return@launch
updateSelectedServer(...)
```

**Bug 2 — OR-logic IP match in `MainSelectionInteractor.hydrateStoredSelectionFromV2()`**

When hydrating a stored `DEFAULT_V2` selection from the refreshed server list, the old code used an OR condition (`configData == stored OR ip == stored`) that collapsed into a single `indexOfFirst` predicate. Because multiple servers in the same country often share the same IP address (e.g. a pool of servers on one relay), this always resolved to the first server sharing that IP regardless of which specific server the user had selected.

Fix: use a priority search — match `configData` first (unique per server config), fall back to IP only when `configData` is blank or yields no match, and default to index 0 as the final fallback.

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

### Diagnosis checklist for future regressions

1. Add a logcat filter for `MainViewModel` and `MainSelectionInteractor` tags. Confirm whether `updateSelectedServer` fires *after* a user selection event — if it does, Bug 1 is regressed.
2. In the `DEFAULT_V2` server list for the affected country, check whether multiple servers share the same IP. If yes, and hydration resolves to the wrong one, Bug 2 is regressed.
3. `pendingUserSelectionOverride` is set in `MainViewModel.onServerSelected()`. If that flag is not being set before `loadInitialSelection()` returns, the race window is wider than expected — investigate coroutine scheduling around `MainViewModel` init.

### Related files

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt` — Bug 1 fix at line 98
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainSelectionInteractor.kt` — Bug 2 fix at lines 168–178
