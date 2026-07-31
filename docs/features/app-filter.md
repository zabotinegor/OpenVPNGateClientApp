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
profile.mAllowedAppsVpn.clear()
profile.mAllowedAppsVpnAreDisallowed = true
if (excluded.isNotEmpty()) profile.mAllowedAppsVpn.addAll(excluded)
```

Two things follow from this and are easy to get wrong:

- **`mAllowedAppsVpnAreDisallowed = true` on every successful pass**, so the engine treats
  `mAllowedAppsVpn` as a *disallow* list. The field name says "allowed"; the flag inverts it.
- The filter is applied **at connect time only**. Changing the selection while connected does not
  re-apply it — the tunnel must be restarted for a change to take effect.

**What happens if the read fails.** The method is wrapped in a `try/catch` that logs a warning and continues, but note the statement
order: `loadExcludedPackages()` is the **first** statement in the `try`. If it throws — a corrupted
or wrong-typed preference makes `getStringSet` raise `ClassCastException` — then neither
`clear()` nor the `mAllowedAppsVpnAreDisallowed` assignment runs. The catch does not substitute a
safe default; it only logs.

The outcome today is still "nothing excluded", but **that is not enforced here**. It holds because
the profile reaching this method is always freshly built by `ConfigParser.convertProfile()`
(`OpenVpnService.kt:849-856`), `ConfigParser` never touches these two fields, and upstream
`VpnProfile` initializes `mAllowedAppsVpnAreDisallowed = true` with an empty `mAllowedAppsVpn`.

That makes the privacy-relevant behaviour a property of **engine defaults in the submodule**, not of
this client's code. If upstream flips that initializer, or if the profile ever arrives from
`ProfileManager` rather than a fresh parse, the failure mode changes silently and nothing in this
method would catch it. Treat it as a known gap rather than a guarantee.

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

*Last verified against: `core/filter/*`, `core/ui/filter/*`, `vpn/OpenVpnService.kt:868-878`, `core/di/CoreDi.kt:152` (2026-07-31).*
