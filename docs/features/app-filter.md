# Per-app filter (split tunneling)

Lets the user exclude specific installed apps from the VPN tunnel. Excluded apps use the normal
network connection while the VPN is up.

## Index

- [Model](#model)
- [How it reaches the tunnel](#how-it-reaches-the-tunnel)
- [Persistence](#persistence)
- [Listing installed apps](#listing-installed-apps)
- [UI](#ui)
- [Permissions](#permissions)

---

## Model

```kotlin
data class AppFilterEntry(val packageName: String, val label: String, val isSystemApp: Boolean)
enum class AppCategory { USER, SYSTEM }
```

The stored state is a **set of excluded package names** — the filter is a denylist, not an allowlist.
An empty set means every app goes through the tunnel.

## How it reaches the tunnel

`OpenVpnService.applyAppFilter(profile)` runs when the VPN profile is built, immediately before
`applyDnsSettings` and the engine start:

```kotlin
// safe state first -- the read below can throw
profile.mAllowedAppsVpn.clear()
profile.mAllowedAppsVpnAreDisallowed = true
try {
    val excluded = AppFilterStore.loadExcludedPackages(applicationContext)
    if (excluded.isNotEmpty()) profile.mAllowedAppsVpn.addAll(excluded)
} catch (t: Throwable) { /* logs a warning */ }
```

Two things follow from this and are easy to get wrong:

- **`mAllowedAppsVpnAreDisallowed` is always `true`**, so the engine treats `mAllowedAppsVpn` as a
  *disallow* list. The field name says "allowed"; the flag inverts it.
- The filter is applied **at connect time only**. Changing the selection while connected does not
  re-apply it — the tunnel must be restarted for a change to take effect.

**If the read fails, nothing is excluded.** `loadExcludedPackages()` can throw — a corrupted or
wrong-typed preference makes `getStringSet` raise `ClassCastException` — so the two assignments that
establish the safe state deliberately run *before* it, outside the `try`. A failed read therefore
leaves an empty disallow list: everything is routed through the tunnel, which fails toward privacy
rather than toward leaking traffic outside it.

**The statement order is load-bearing; do not "tidy" the read back to the top of the method.** It was
in that position until this was corrected. Nothing broke at the time, because the profile always
arrives freshly built from `ConfigParser.convertProfile()` (`OpenVpnService.kt:849-856`),
`ConfigParser` never touches these two fields, and upstream `VpnProfile.java:158` initializes
`mAllowedAppsVpnAreDisallowed = true` with an empty `mAllowedAppsVpn`. That made a privacy-relevant
guarantee depend on engine-submodule defaults and on the profile being fresh — neither of which this
client controls. `OpenVpnServiceAppFilterTest` now pins the behaviour down here.

## Persistence

`AppFilterStore`, a `SharedPreferences` file separate from the main settings:

| | |
|---|---|
| Prefs file | `app_filter` |
| Key | `excluded_packages` (string set) |

`saveExcludedPackages` sanitizes the incoming set before writing. `updateExcludedPackages` applies a
mutation lambda to the current set and persists the result.

## Listing installed apps

`AppFilterRepository` / `DefaultAppFilterRepository` (registered in `CoreDi`) enumerate installed
packages and classify each as `USER` or `SYSTEM` via `AppFilterEntry.isSystemApp`, which is what the
two-tab UI is built on.

## UI

`ui/filter/FilterActivity` with `FilterViewModel`, `FilterContract`, `FilterListAdapter`,
`FilterPageFragment` (one per `AppCategory`), and `FilterLogger`.

## Permissions

Enumerating other installed apps needs both:

- `QUERY_ALL_PACKAGES` — declared in the **engine** manifest, not the app's
- a `<queries>` LAUNCHER intent block — in the core manifest

See [../reference/permissions.md](../reference/permissions.md). If package enumeration ever returns
only this app on a new Android level, that split is the first place to look.

*Last verified against: `core/filter/*`, `core/ui/filter/*`, `vpn/OpenVpnService.kt:868-884`, `core/di/CoreDi.kt:152`, `vpn/OpenVpnServiceAppFilterTest.kt` (2026-08-01).*
