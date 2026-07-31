# Settings

User preferences, how they are persisted, and what applying them actually does.

## Index

- [Store](#store)
- [Language and theme](#language-and-theme)
- [Server source](#server-source)
- [Values that are clamped](#values-that-are-clamped)
- [Settings that only apply on reconnect](#settings-that-only-apply-on-reconnect)

---

## Store

`settings/UserSettingsStore` is the single persistence point, behind `SettingsRepository` /
`DefaultSettingsRepository` with defaults in `SettingsDefaults`. UI lives in `ui/settings/`.

The full key list with defaults, clamps and migrations is in
[../reference/settings-keys.md](../reference/settings-keys.md).

One setting deliberately lives outside this store: the per-app filter's excluded-package set, in its
own `app_filter` prefs file. See [app-filter.md](app-filter.md).

## Language and theme

```kotlin
LanguageOption { SYSTEM, ENGLISH, RUSSIAN, POLISH }
ThemeOption    { SYSTEM, LIGHT, DARK }
```

`UserSettingsStore.applyThemeAndLocale()` calls `AppCompatDelegate.setApplicationLocales`, so the
locale is an **app-level override** rather than a manual resource swap.
`resolvePreferredLocale()` maps `SYSTEM` to the runtime locale's language code, falling back to `en`
when blank. Resource sets exist for `values-ru`, `values-pl`, and `values-night` for dark theme.

The chosen language also flows outward as the `locale` query parameter on server-list and release-note
requests — see [localization is handled server-side](server-sync.md) for what the backend does with it.

Changing language mid-session triggers selected-country relocalization rather than forcing the user to
re-pick a country.

## Server source

Two values only: `DEFAULT_V2` (default) and `VPNGATE`. Persisted `DEFAULT`, `LEGACY` and `CUSTOM`
values from older builds are migrated to `DEFAULT_V2` on load. Anything describing four modes is
describing a version that no longer exists.

## Values that are clamped

- `cache_ttl_ms` — default 20 minutes, **minimum 1 minute**. A smaller persisted value is raised on
  read, not rejected.
- `status_stall_timeout_seconds` — default 5, **minimum 1**. Renamed from
  `auto_switch_timeout_seconds`; both the rename and the clamp are applied in `load()`.

Because clamping and migration happen in `load()`, they are invisible to callers — but a test that
writes a raw preference value must use the current key name and expect the clamped result back.

## Settings that only apply on reconnect

Two settings are read when the VPN profile is built, not continuously:

- **DNS override** — [dns.md](dns.md)
- **Per-app filter** — [app-filter.md](app-filter.md)

Changing either while connected has no effect until the tunnel restarts. This is worth stating in the
UI when it is not obvious; it is a common source of "the setting doesn't work" reports.

`auto_switch_within_country` and `status_stall_timeout_seconds` are read live by the auto-switcher —
see [connection-recovery.md](connection-recovery.md).

*Last verified against: `core/settings/UserSettingsStore.kt`, `core/settings/SettingsDefaults.kt`, `core/ui/settings/*` (2026-07-31).*
