# DNS provider override

Lets the user replace the DNS servers pushed by the VPN server with a public resolver, or keep the
server-provided ones.

## Index

- [Options](#options)
- [Resolution](#resolution)
- [Default and fallback behaviour](#default-and-fallback-behaviour)
- [Persistence](#persistence)
- [UI](#ui)

---

## Options

`DnsOption` (`core/dns/DnsOption.kt`) has **eight** values. `SERVER` means "do not override"; the
other seven each map to a public resolver pair defined in `DnsOptions.providers`:

| Option | Label | Primary | Secondary |
|---|---|---|---|
| `SERVER` | — | *(uses whatever the VPN server pushes)* | — |
| `GOOGLE` | Google Public DNS | `8.8.8.8` | `8.8.4.4` |
| `CLOUDFLARE` | Cloudflare | `1.1.1.1` | `1.0.0.1` |
| `QUAD9` | Quad9 | `9.9.9.9` | `149.112.112.112` |
| `OPENDNS` | OpenDNS | `208.67.222.222` | `208.67.220.220` |
| `ADGUARD` | AdGuard DNS | `94.140.14.14` | `94.140.15.15` |
| `CLEANBROWSING` | CleanBrowsing | `185.228.168.9` | `185.228.169.9` |
| `DNSWATCH` | DNS.Watch | `84.200.69.80` | `84.200.70.40` |

Labels are literals in `DnsOptions`, not string resources — they are brand names and are not
translated.

## Resolution

`DnsOptions.resolve(option)` returns a `DnsConfig`:

```kotlin
data class DnsConfig(val overrideDns: Boolean, val primary: String? = null, val secondary: String? = null)
```

- `SERVER` → `DnsConfig(overrideDns = false)` — no addresses, the tunnel keeps the pushed DNS.
- Any provider → `DnsConfig(true, primary, secondary)`.

## Default and fallback behaviour

The feature is **fail-safe toward the server's own DNS**, in two places:

- `DnsOption.fromString(name)` returns **`SERVER`** for an unknown or null persisted value. A
  renamed or removed enum constant degrades to "no override" rather than throwing.
- `resolve()` also returns `overrideDns = false` if an option has no matching entry in `providers` —
  so adding an enum constant without adding its provider row silently disables the override for that
  option rather than crashing.

That second path is worth knowing when adding a provider: **add the enum value and the
`DnsOptions.providers` row together**, or the new option appears in the UI and does nothing.

## Persistence

Stored through `DnsSettingsRepository` under the `dns_option` key alongside the other user settings —
see [../reference/settings-keys.md](../reference/settings-keys.md).

## UI

`ui/dns/DnsActivity` with `DnsViewModel`, `DnsContract`, `DnsOptionAdapter`, and `DnsLogger` for
diagnostics. A single-choice list; selection writes through the repository immediately.

Covered by `DnsOptionsTest` and `DnsViewModelTest` (JVM unit tests).

*Last verified against: `core/dns/DnsOption.kt`, `core/dns/DnsOptions.kt`, `core/dns/DnsSettingsRepository.kt`, `core/ui/dns/*` (2026-07-31).*
