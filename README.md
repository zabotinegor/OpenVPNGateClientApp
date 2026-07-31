# Client for OpenVPN Gate

Open-source Android VPN client for connecting to VPN Gate and compatible server lists.
The app is built on top of the `ics-openvpn` engine (GPLv2) and ships as two launchers (`mobile`, `tv`) over one shared `core` module.

- Homepage: https://openvpngateclient.azurewebsites.net
- GitHub (app): https://github.com/zabotinegor/OpenVPNGateClientApp
- GitHub (engine submodule): https://github.com/zabotinegor/OpenVPNGateClientEngine
- Privacy Policy: https://openvpngateclient.azurewebsites.net/privacy-policy
- Terms of Use: https://openvpngateclient.azurewebsites.net/terms-of-use
- License: [GPL-2.0-only](LICENSE)

## Repository Layout
- `src/core` - shared UI, networking, settings, VPN orchestration.
- `src/mobile` - phone/tablet launcher.
- `src/tv` - Android TV launcher.
- Splash/startup flow is centralized in `src/core` (`SplashActivityCore` + `SplashServerPreloadInteractor`), while `mobile` and `tv` keep thin launcher wrappers.
- `src/openVpnEngine` -> `src/external/OpenVPNEngine/main` (git submodule).
- `media` - private media submodule used for app icon/banner assets.

## Tech Stack
- Kotlin, Android SDK 24+ (app modules target/compile 36; the engine submodule needs SDK 37 — see [docs/reference/build-config.md](docs/reference/build-config.md))
- ViewBinding
- Retrofit + OkHttp (network)
- Koin (DI)
- Timber (logging)
- Gradle Kotlin DSL

## Prerequisites
- JDK 11
- Android SDK/Build Tools for compile SDK 36, **plus SDK Platform 37** for the engine submodule
- Git submodules initialized

```bash
git submodule update --init --recursive
```

## Required Build Configuration
The `core` module requires the following endpoints at build time:
- `PRIMARY_SERVERS_URL` — primary backend base URL only, for example `https://openvpngateclient.azurewebsites.net`
- `FALLBACK_SERVERS_URL` — full VPN Gate CSV fallback URL, for example `https://www.vpngate.net/api/iphone/`

Runtime routes are derived in code from `PRIMARY_SERVERS_URL`:
- V2 countries: `{PRIMARY_SERVERS_URL}/api/v2/servers/countries/active`
- V2 servers: `{PRIMARY_SERVERS_URL}/api/v2/servers`
- Release notes and update checks stay on the trusted primary backend host regardless of the selected server source

The full route list, including which endpoints exist but are never called, is in
[docs/reference/api-endpoints.md](docs/reference/api-endpoints.md).

### Fallback Behavior (DEFAULT_V2)
When the app loads servers via `DEFAULT_V2` and the primary v2 route fails, it falls back **directly**
to the VPN Gate CSV (`FALLBACK_SERVERS_URL`). There are two steps, not three — there is no
intermediate legacy-CSV hop on the primary domain.

If the fallback succeeds, `VPNGATE` is persisted as the working source. This applies to all shared
sync entry points (splash, main foreground, settings source-switch, periodic background refresh).

### Localization Behavior (DEFAULT_V2)
`DEFAULT_V2` requests include an explicit `locale` query for both v2 countries and per-country v2 server lists.

Locale mapping uses app language settings:
- `SYSTEM` -> runtime locale language code (fallback `en` when blank)
- `ENGLISH` -> `en`
- `RUSSIAN` -> `ru`
- `POLISH` -> `pl`

This localization behavior is scoped to `DEFAULT_V2` only; `VPNGATE` request behavior is unchanged.

### Selected-Country Relocalization (DEFAULT_V2)
When app language changes and the selected source is `DEFAULT_V2`, the app relocalizes an already selected country/server in the same session without requiring manual reselection.

Relocalization behavior:
- Country **resolution** is code-first: the stable code is stored per server, and the persisted country display name is rewritten on language change rather than matched against. See [docs/features/server-selection.md](docs/features/server-selection.md).
- Persisted selected-country display name is rewritten to the active locale after server list alignment.
- Current server identity and index are preserved when still present; if the server disappears, deterministic safe fallback is applied.

This relocalization path is not executed for the `VPNGATE` source.

Resolution order in build scripts:
1. Gradle property (`-P...`)
2. Environment variable
3. `servers.local.json`

### Local file override (not committed)
Create `servers.local.json` in either repository root or `src/`. If both exist **`src/` wins** and
the root copy is ignored — see [docs/reference/build-config.md](docs/reference/build-config.md).

```json
{
  "PRIMARY_SERVERS_URL": "https://openvpngateclient.azurewebsites.net",
  "FALLBACK_SERVERS_URL": "https://www.vpngate.net/api/iphone/"
}
```

## Signing Configuration (release)
Create `src/keystore.properties`:

```properties
keyAlias=...
keyPassword=...
storePassword=...
storeFile=keystore.jks
```

Place `keystore.jks` next to `keystore.properties` (not tracked in git).

If `src/keystore.properties` is missing, local `release` builds can still be produced as unsigned artifacts.
Provide signing properties when you need signed release outputs for distribution.

## Media Assets
`mobile` and `tv` run `copyAndRenameDrawables` before `preBuild`.
The task copies assets from `media/Logos` or `media/Logo` into module resources.

Expected files (with fallbacks):
- App icon:
  - `appicon_GP_512x512.png`
  - fallback: `appicon.png`
- Banner:
  - `appbanner_GP_1280x720.png`
  - fallbacks: `appdesc_GP_1024x500.png`, `logo_with_text_1536x1024.png`

Build fails if required media files are missing.

## Build and Test
Run from repository root:

```bash
cd src
```

### Build all app variants
```bash
# Debug APKs (mobile + tv)
./gradlew assembleDebugApp
# Windows
.\gradlew.bat assembleDebugApp

# Release APKs (mobile + tv)
./gradlew assembleReleaseApp -PappVersionName=1.0.0 -PappVersionCode=1 -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...

# Release AABs (mobile + tv)
./gradlew bundleReleaseApp -PappVersionName=1.0.0 -PappVersionCode=1 -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...
```

### Version override per launcher
- `appVersionCodeMobile`
- `appVersionCodeTv`
- fallback common value: `appVersionCode`

### Unit tests
```bash
./gradlew testDebugUnitTestApp
```


### Build performance
The project is configured for optimized build performance in `src/gradle.properties`. These settings enable parallel execution, build caching, and increased heap memory to reduce iteration times. Additionally, the OpenVPN engine SWIG generation tasks are configured to be cache-eligible, allowing unchanged code generation to be restored from the local build cache.

**Default Gradle properties** (US-05 optimization):
- `org.gradle.parallel=true` — Enable parallel module execution
- `org.gradle.jvmargs=-Xmx4096m` — Increase JVM heap to 4 GB
- `org.gradle.workers.max=8` — Cap worker threads
- `org.gradle.caching=true` — Enable local build cache
- `org.gradle.configureondemand=true` — Configure only required modules

The performance baseline and its validation evidence are recorded in ClickUp; the git-side story and QA folders were removed in the 2026-07-30 migration.

## Documentation

**[docs/INDEX.md](docs/INDEX.md) is the catalog for all technical documentation** in this repository
— feature behaviour, reference tables, how-to guides, troubleshooting, device QA notes and the
conventions contributors follow. It lists each document with a one-line description, so you can open
only the one you need.

Manual QA automation scripts live with the scripts themselves:
[tests/manual-e2e/automation/README.md](tests/manual-e2e/automation/README.md). Per-story QA specs,
cases and suites are managed in ClickUp, not in this repository.

## Runtime Behavior (from current code)
- App starts with a shared splash flow: one GIF loop and parallel server preload. Main screen opens when both stages are complete.
- If preload outlives GIF playback, splash shows a loading spinner until preload completes.
- If preload fails, startup still continues to main screen; fallback is logged as a warning.
- Main details display contract is split by field and source:
  - `Server` field shows selected position as `current/total` within the selected country list (for example `6/7`) for all sources.
  - `Address` field shows city + UTC for `DEFAULT_V2` when city metadata is available, city only when UTC is missing, and the selected server IP for non-`DEFAULT_V2` sources or when city metadata is unavailable.
- Server source modes in settings — there are **two**:
  - `DEFAULT_V2`: v2 API (default for fresh installs)
  - `VPNGATE`: fallback URL only
  - Persisted `LEGACY`, `DEFAULT` and `CUSTOM` values from older builds are migrated to `DEFAULT_V2` on load.
- Server list cache with configurable TTL (`cacheTtlMs`, default 20 minutes, minimum 1 minute).
- DNS provider selection is applied on next VPN connection.
- Auto-switch within selected country with stall timeout settings.
- Connected-state health watchdog evaluates traffic delta and trusted endpoint probe while connected.
- Sustained unhealthy connected state triggers bounded auto-recovery with cooldown/debounce; success resets watchdog counters and reconnecting hints.
- Recovery retry exhaustion transitions to a deterministic fail-safe disconnect state instead of indefinite false-connected presentation.
- A fresh start action clears stale pending-stop intent so previous stop teardown state cannot suppress a new user start.
- Shared package/application ID across mobile and tv modules.

## Logging and Diagnostics
- Screen flow logs and VPN session logs are written via app logging trees.
- Startup fallback paths (for example, splash preload or splash GIF load failures) are recorded as warning-level logs.
- Watchdog decision logs include privacy-safe context for unhealthy thresholds, recovery attempt index, and healthy recovery restoration.
- About screen supports exporting recent logcat archive for diagnostics.

## AI Agent Documentation
- [AGENTS.md](AGENTS.md): repository-level operational rules for coding agents.
- [docs/INDEX.md](docs/INDEX.md): the knowledge-base catalog — start here for anything behavioural.
- `AGENTS.local.md`, `README.local.md`: optional local-only overrides for machine-specific paths and
  environment notes. Both are gitignored, so they are absent on a fresh clone by design.
- `.github/agents/` and `.github/skills/`: agent and skill definitions, mirrored in from CopilotTools
  by `agent-sync`. The registry and frontmatter schema that govern them live in CopilotTools and are
  not synced into this repository.

## Legal and Privacy
Canonical documents:
- [PRIVACY_POLICY.md](PRIVACY_POLICY.md)
- [TERMS.md](TERMS.md)

The app links to hosted canonical pages and local copies are kept in sync for repository transparency.

## Licensing
This project, including the bundled `ics-openvpn` fork, is distributed under GPL-2.0-only.
Review `LICENSE` and upstream notices in `src/external/OpenVPNEngine/doc/LICENSE.txt` before redistribution.


