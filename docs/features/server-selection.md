# Server selection and persistence

How the app remembers which country and server the user chose, and keeps that choice valid as the
server list changes underneath it.

## Index

- [What is persisted](#what-is-persisted)
- [Version signal](#version-signal)
- [Bootstrap](#bootstrap)
- [Relocalization on language change](#relocalization-on-language-change)
- [When the selected server disappears](#when-the-selected-server-disappears)

---

## What is persisted

`servers/SelectedCountryStore` holds the selected country and the server chosen within it, along with
the display name shown on the main screen.

**The `selected_country` key holds the localized display name, not a code.** The stable identity
lives one level down: each entry in `selected_country_servers` carries `code` (from
`Server.country.code`), and that is what survives a language change.

This distinction matters because the two are easy to conflate:

| Stored as | Where | Survives relocalization |
|---|---|---|
| localized country name | `selected_country` | no — it is **rewritten** when the language changes |
| `countryCode` | each stored server in `selected_country_servers` | yes — this is the recovery key |

So a language change is survivable not because the name is stable, but because the code is recoverable
and the name is then explicitly rewritten. `ServersV2SyncCoordinator` reads the code from
`SelectedCountryStore.currentServer()?.countryCode` (falling back to the first stored server), resolves
the country's new localized name, and calls `updateSelectedCountryNameIfCurrent`, which re-checks the
expected name under `selectionRenameLock` before writing and skips the rename if the selection moved
underneath it.

Do not remove the code-based lookup or the rename step on the assumption that `selected_country` is
already a stable identifier. It is not.

## Version signal

`servers/SelectedCountryVersionSignal` marks the persisted selection as potentially stale when the
underlying list changes. Consumers react to the signal rather than polling the store, so a refresh
that does not affect the selection costs nothing.

## Bootstrap

`servers/SelectionBootstrap` establishes a valid selection at startup — restoring the persisted one
when it is still present in the current list, or choosing a deterministic default when it is not.
`ServerSelectionResult` is the outcome type callers branch on.

Sync itself is covered in [server-sync.md](server-sync.md).

## Relocalization on language change

When the app language changes under `DEFAULT_V2`, the app relocalizes an already-selected
country/server **in the same session**, without asking the user to reselect:

1. Resolve the country by code, not by localized name.
2. Rewrite the persisted display name to the active locale after the server list realigns.
3. Preserve the current server identity and index when it is still present.

This path does not run for the `VPNGATE` source, which has no localized names to realign.

## When the selected server disappears

A deterministic safe fallback is applied rather than clearing the selection — the user keeps a working
country and a valid server rather than being returned to an empty state.

The main screen's `Server` field shows position as `current/total` within the selected country, so a
fallback is visible to the user as a changed index rather than silently swapping underneath them.

Latency display and the `SignalStrength` buckets are covered in [ui-shell.md](ui-shell.md#server-list-screens);
favourites interaction is in [favorites.md](favorites.md).

*Last verified against: `servers/SelectedCountryStore.kt`, `servers/SelectedCountryVersionSignal.kt`, `servers/SelectionBootstrap.kt`, `servers/ServerSelectionResult.kt` (2026-07-31).*
