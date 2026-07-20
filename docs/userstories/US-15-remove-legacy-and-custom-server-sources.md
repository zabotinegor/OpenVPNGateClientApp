# US-15 — Remove Legacy and Custom server sources

## Context

The "Server list source" setting currently offers 4 options: **Client for OpenVPN Gate** (server_v2, our endpoint, extended features), **Client for OpenVPN Gate (Legacy)** (our old CSV endpoint), **VPN Gate** (third-party, CSV-only, not our endpoint), and **Custom** (user-entered URL). We are reducing this to 2 user-facing options: **Client for OpenVPN Gate** (server_v2) and **VPN Gate**.

`ServerSource.LEGACY` is currently more than a UI option — `ServerSelectionSyncCoordinator` uses it as an internal resilience fallback when server_v2 sync fails, and `ServerRepository` can further downgrade a LEGACY fallback to `VPNGATE`. Per BA decision, this internal fallback chain is being rewired to go straight from server_v2 failure to VPNGATE, and the `LEGACY` enum value is removed entirely (not just hidden).

## Decisions (confirmed with user)

1. **`ServerSource.LEGACY` is deleted entirely.** The v2-failure fallback path is rewired to fall back directly to `VPNGATE` instead of routing through the legacy CSV endpoint first.
2. **Migration:** any persisted `server_source` of `LEGACY` or `CUSTOM` (including the pre-existing legacy `"DEFAULT"` string) is silently rewritten to `DEFAULT_V2` on next load.
3. **`ServerSource.CUSTOM` is fully deleted**, including the URL input field, its ViewModel/Activity wiring, and the persisted custom-URL preference (stops being read/written).

## Acceptance Criteria

- AC1: Settings screen "Server list source" shows exactly 2 radio options: "Client for OpenVPN Gate" and "VPN Gate". The Legacy radio button, Custom radio button, and Custom URL input field are removed from the layout.
- AC2: `ServerSource` enum contains only `DEFAULT_V2` and `VPNGATE`. All exhaustive `when` branches on `ServerSource` across the codebase compile against the 2-value enum (no `else` branches added to paper over missing cases where an explicit branch was previously required).
- AC3: `ServerSelectionSyncCoordinator`'s v2-failure fallback goes directly to `VPNGATE` (no intermediate LEGACY step). `ServerRepository`'s LEGACY→VPNGATE downgrade-on-secondary-URL logic is removed/folded into the new direct-VPNGATE path since LEGACY no longer exists.
- AC4: On app launch, a persisted `server_source` preference value of `LEGACY`, `CUSTOM`, or the legacy `"DEFAULT"` string is migrated to `DEFAULT_V2`. No crash or unresolved-enum error occurs for users upgrading from a build where Legacy/Custom was selected.
- AC5: The persisted custom-URL preference is no longer read or written anywhere; `UserSettingsStore` no longer exposes custom-URL get/set.
- AC6: String resources `settings_server_default` (Legacy label) and `settings_server_custom`/`settings_server_custom_hint` are removed from `values`, `values-ru`, `values-pl` `strings.xml`.
- AC7: Regression check: server_v2-exclusive features (city+UTC display, locale-parameterized queries, selected-country relocalization on language change, two-phase lazy loading, hardprobe server-ID enqueue) are unaffected for server_v2 and continue to be correctly gated as v2-only — VPNGATE (CSV-only) users still see IP-based display, no relocalization, and no hardprobe enqueue (id=0 suppression), matching pre-change parity behavior for the sources that remain.
- AC8: Regression check: existing auto-switch, startup server selection, and manual refresh flows work correctly for both remaining sources.

## Out of scope

- Any change to server_v2 API contract or VPN Gate CSV parsing format.
- Any new server source additions.

## Risk / Regression Notes

- `docs/server-sync-flow.md` and any user stories referencing LEGACY/CUSTOM behavior parity (US-02) should be checked for statements that no longer apply once LEGACY is removed — doc updates happen in Step 7 (Docs), not here, but implementation should flag any doc drift found.
- `UserSettingsStore.resolveServerUrls` and any other `enumValueOf`/`ServerSource.values()` lookups must be audited so a stale persisted string doesn't throw at runtime.
