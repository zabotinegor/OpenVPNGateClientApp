# Settings keys

User settings persisted through `UserSettingsStore`, plus the one store that lives outside it.

## Keys

| Key | Type | Default | Constraint |
|---|---|---|---|
| `language` | `LanguageOption` | `SYSTEM` | `SYSTEM`, `ENGLISH`, `RUSSIAN`, `POLISH` |
| `theme` | `ThemeOption` | `SYSTEM` | `SYSTEM`, `LIGHT`, `DARK` |
| `server_source` | `ServerSource` | `DEFAULT_V2` | **two values only** — see below |
| `cache_ttl_ms` | Long | `1_200_000` (20 min) | clamped to a minimum of `60_000` |
| `auto_switch_within_country` | Boolean | **on** | gates `ServerAutoSwitcher`; defaults to `true`, so a user opts *out* |
| `status_stall_timeout_seconds` | Int | `5` | minimum `1`; migrated from `auto_switch_timeout_seconds` |
| `dns_option` | `DnsOption` | `SERVER` | 8 values, see [../features/dns.md](../features/dns.md) |

Separate store, separate prefs file:

| Prefs file | Key | Contents |
|---|---|---|
| `app_filter` | `excluded_packages` | string set of package names excluded from the tunnel |

## `ServerSource` has two values, not four

```kotlin
enum class ServerSource { VPNGATE, DEFAULT_V2 }
```

`UserSettingsStore.load()` **silently migrates** persisted `"DEFAULT"`, `"LEGACY"` and `"CUSTOM"` to
`DEFAULT_V2`. Those names were deleted from the enum; any document listing four modes, or describing
`CUSTOM` fallback behaviour, is describing a version of the app that no longer exists.

## Migrations to be aware of

- `auto_switch_timeout_seconds` → `status_stall_timeout_seconds`. Both the rename and the minimum
  clamp are applied on load.
- The three removed `ServerSource` names above.

Persisted-value migrations are applied in `load()`, so they are transparent to callers — but a test
that writes a raw pref value must use the current key name.

## Locale and theme application

`UserSettingsStore.applyThemeAndLocale()` calls `AppCompatDelegate.setApplicationLocales`;
`resolvePreferredLocale()` maps `SYSTEM` to the runtime locale language code with an `en` fallback
when blank. Resource sets exist for `values-ru`, `values-pl` and `values-night`.

The locale also flows into API queries as the `locale` parameter — see
[api-endpoints.md](api-endpoints.md).

*Last verified against: `core/settings/UserSettingsStore.kt`, `core/settings/SettingsDefaults.kt`, `core/filter/AppFilterStore.kt` (2026-07-31).*
