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
- Kotlin, Android SDK 24+ (target/compile 36)
- ViewBinding
- Retrofit + OkHttp (network)
- Koin (DI)
- Timber (logging)
- Gradle Kotlin DSL

## Prerequisites
- JDK 11
- Android SDK/Build Tools compatible with compile SDK 36
- Git submodules initialized

```bash
git submodule update --init --recursive
```

## Required Build Configuration
The `core` module requires the following endpoints at build time:
- `PRIMARY_SERVERS_URL`
- `FALLBACK_SERVERS_URL`
- `PRIMARY_SERVERS_V2_URL` — base URL for the v2 API (e.g. `https://openvpngateclient.azurewebsites.net`)

Resolution order in build scripts:
1. Gradle property (`-P...`)
2. Environment variable
3. `servers.local.json`

### Local file override (not committed)
Create `servers.local.json` in either repository root or `src/`:

```json
{
  "PRIMARY_SERVERS_URL": "https://example.com/api/v1/servers/active",
  "FALLBACK_SERVERS_URL": "https://www.vpngate.net/api/iphone/",
  "PRIMARY_SERVERS_V2_URL": "https://openvpngateclient.azurewebsites.net"
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
./gradlew assembleReleaseApp -PappVersionName=1.0.0 -PappVersionCode=1 -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=... -PPRIMARY_SERVERS_V2_URL=...

# Release AABs (mobile + tv)
./gradlew bundleReleaseApp -PappVersionName=1.0.0 -PappVersionCode=1 -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=... -PPRIMARY_SERVERS_V2_URL=...
```

### Version override per launcher
- `appVersionCodeMobile`
- `appVersionCodeTv`
- fallback common value: `appVersionCode`

### Unit tests
```bash
./gradlew testDebugUnitTestApp
```


### Build performance defaults (US-05)
Default Gradle tuning in src/gradle.properties now enables faster local and CI iteration for app builds:

- org.gradle.parallel=true
### Build performance
The project is configured for optimized build performance in `src/gradle.properties`. These settings enable parallel execution, build caching, and increased heap memory to reduce iteration times. Additionally, the OpenVPN engine SWIG generation tasks are configured to be cache-eligible, allowing unchanged code generation to be restored from the local build cache.

For more details on the performance baseline and validation evidence, refer to the [US-05 documentation](docs/userstories/US-05-gradle-build-optimization.md) and the [evidence index](tests/manual-e2e/stories/us-05-gradle-build-performance-optimization/suites/us-05-evidence-index.md).
## Manual E2E Documentation
- Entry point: [tests/manual-e2e/README.md](tests/manual-e2e/README.md)
- Automation helpers: [tests/manual-e2e/automation/README.md](tests/manual-e2e/automation/README.md)
- Specifications: [tests/manual-e2e/specs](tests/manual-e2e/specs)
- Suites: [tests/manual-e2e/suites](tests/manual-e2e/suites)
- Test cases: [tests/manual-e2e/cases](tests/manual-e2e/cases)
- Run artifacts/evidence: [artifacts/manual-qa](artifacts/manual-qa)

## Runtime Behavior (from current code)
- App starts with a shared splash flow: one GIF loop and parallel server preload. Main screen opens when both stages are complete.
- If preload outlives GIF playback, splash shows a loading spinner until preload completes.
- If preload fails, startup still continues to main screen; fallback is logged as a warning.
- Server source modes in settings:
  - `LEGACY`: primary then fallback URL
  - `DEFAULT_V2`: v2 API (default for fresh installs)
  - `VPNGATE`: fallback URL only
  - `CUSTOM`: user-provided URL
- Server list cache with configurable TTL (`cacheTtlMs`, default 20 minutes).
- If VPN is connected, server refresh is locked to cache.
- DNS provider selection is applied on next VPN connection.
- Auto-switch within selected country with stall timeout settings.
- Shared package/application ID across mobile and tv modules.

## Logging and Diagnostics
- Screen flow logs and VPN session logs are written via app logging trees.
- Startup fallback paths (for example, splash preload or splash GIF load failures) are recorded as warning-level logs.
- About screen supports exporting recent logcat archive for diagnostics.

## AI Agent Documentation
- [AGENTS.md](AGENTS.md): repository-level operational rules for coding agents.
- [AGENTS.local.md](AGENTS.local.md): local-only backend and environment overrides (do not commit).
- [README.local.md](README.local.md): local documentation overlay for machine-specific notes.
- [.github/AGENTS-REGISTRY.md](.github/AGENTS-REGISTRY.md): source-of-truth mapping between agents and skills.
- [.github/FRONTMATTER-SCHEMA.md](.github/FRONTMATTER-SCHEMA.md): frontmatter contract for .agent.md and SKILL.md files.

## Legal and Privacy
Canonical documents:
- [PRIVACY_POLICY.md](PRIVACY_POLICY.md)
- [TERMS.md](TERMS.md)

The app links to hosted canonical pages and local copies are kept in sync for repository transparency.

## Licensing
This project, including the bundled `ics-openvpn` fork, is distributed under GPL-2.0-only.
Review `LICENSE` and upstream notices in `src/external/OpenVPNEngine/doc/LICENSE.txt` before redistribution.


