# Client for OpenVPN Gate

Open-source Android client for connecting to the public VPN Gate network. The app is built on top of the `ics-openvpn` engine (GPLv2) and ships mobile and TV variants that share a common core UI/logic module.

- Homepage: https://openvpngateclient.azurewebsites.net
- GitHub (app): https://github.com/zabotinegor/OpenVPNGateClientApp
- GitHub (engine submodule): https://github.com/zabotinegor/OpenVPNGateClientEngine
- Privacy Policy: https://openvpngateclient.azurewebsites.net/privacy-policy (copy in [PRIVACY_POLICY.md](PRIVACY_POLICY.md))
- Terms of Use / Disclaimer: https://openvpngateclient.azurewebsites.net/terms-of-use (copy in [TERMS.md](TERMS.md))
- Google Play: internal testing planned (AAB builds ready; link will appear after publish)
- License: [GPL-2.0-only](LICENSE)

## Media assets (icons/banners/screenshots)
- Media is stored in a private submodule (`media`, repo: OpenVPNGateClientMedia). Fetch it before building:
  ```bash
  git submodule update --init --recursive
  ```
- Gradle task `copyAndRenameDrawables` (applied to `mobile` and `tv`) copies assets from `media/Logo|Logos` into module resources:
  - `appicon_GP_512x512.png` (fallback `appicon.png`) -> `src/main/res/drawable/appicon.png`
  - `appbanner_GP_1280x720.png` (fallback `appdesc_GP_1024x500.png`, then `logo_with_text_1536x1024.png`) -> `src/main/res/drawable-nodpi/banner.png`
  The build fails if required assets are missing.

## Features
- OpenVPN-based client with the `ics-openvpn` engine (bundled as the `openVpnEngine` submodule)
- Server catalog pulled from a primary endpoint with automatic fallback to VPN Gate public feed; cached on disk with TTL
- Manual country selection with per-country server picker and core connection stats (speed, duration, IP, status)
- DNS selection screen with provider presets applied on next connection
- Auto-switch within a country with full-cycle retry and configurable stall timer
- Status source gating (AIDL vs VpnStatus) with stale snapshot protection and rebind logic
- Server position indicator (current/total) and IP synchronization across selection/connect states
- Separate mobile and TV launchers sharing the same core UI and networking code
- Per-app filtering (user/system), Select all toggles, pinned info card, and TV-friendly focus/scroll restoration
- Server list refresh button with localized label + icon and focus bounce feedback
- Server list parsing/caching is streaming; configs are loaded lazily per selection to reduce memory/GC pressure
- Status snapshots persisted in the engine with AIDL sync for reliable relaunch/idle recovery
- User server selection while connected/connecting stops the current VPN session
- Refresh is locked to cache while VPN is connected to avoid blocked network access

## Runtime logging
- Minimal screen flow logs are emitted on enter/exit for key screens.
- DNS selection is logged when changed and when applied at VPN start.

## Stack and Modules
- Kotlin, Android SDK 24+, ViewBinding, Retrofit/OkHttp
- Modules:
  - `mobile` - phone/tablet app
  - `tv` - TV flavor
  - `core` - shared UI, networking, and VPN orchestration
  - `external/OpenVPNEngine` - fork of `ics-openvpn` (GPLv2)
- Build system: Gradle (Kotlin DSL), Android Gradle Plugin

## Configuration
- Required endpoints are read from Gradle properties or env vars:
  - `PRIMARY_SERVERS_URL`
  - `FALLBACK_SERVERS_URL`
- Local override (not committed): create `servers.local.json` either in repo root or `src/`:
  ```json
  {
    "PRIMARY_SERVERS_URL": "https://example.com/api/v1/servers/active",
    "FALLBACK_SERVERS_URL": "https://www.vpngate.net/api/iphone/"
  }
  ```
- Signing (release): place `keystore.properties` in `src/` with
  ```
  keyAlias=...
  keyPassword=...
  storePassword=...
  storeFile=keystore.jks
  ```
  and keep the referenced `.jks` file alongside it (not tracked in git).

## Building and Running
From the repository root:
```bash
cd src
```

- Debug APK (mobile):
  ```bash
  ./gradlew :mobile:assembleDebug     # macOS/Linux
  .\gradlew.bat :mobile:assembleDebug # Windows
  ```
- Release APK:
  ```bash
  ./gradlew :mobile:assembleRelease \
    -PappVersionName=1.0.0 -PappVersionCode=1 \
    -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...
  ```
- Google Play / AAB (mobile):
  ```bash
  ./gradlew :mobile:bundleRelease \
    -PappVersionName=1.0.0 -PappVersionCode=1 \
    -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...
  ```
- TV builds follow the same pattern with the `:tv:` module.

### Server caching/runtime notes
- Server CSV responses are streamed directly to a cache file (`cacheDir/servers_<hash>.csv`); no intermediate base64/strings.
- Cache freshness is controlled by `cacheTtlMs` in user settings (`DEFAULT_CACHE_TTL_MS` fallback).
- On cache miss/stale, we try primary, then fallback; on failure we return stale cache if present and log the fallback.
- When VPN is connected, server list is served from cache only (ignores TTL) and manual refresh is disabled.
- Server list stores only summaries; configs are loaded lazily via `loadConfigs()` when a country is picked.
- Auto-switch timeouts use different thresholds for CONNECTING_NO_REPLY vs CONNECTING_REPLIED, plus idle tolerance for UNKNOWN/PAUSED.
- Status snapshots ignore stale data when live AIDL updates are recent; repeated stale snapshots trigger a status rebind.

## CI/CD
GitHub Actions workflows are located in `.github/workflows` and build signed release artifacts from `dev`, `main`, and tags (requires secrets for signing and server URLs). Dev builds now also produce `.aab` bundles for Play internal testing.
Tag and asset naming includes the build number (GitHub run number) to ensure each run is unique:
- Stable auto: `v1.2.3-auto(45)`
- Stable tag: `v1.2.3(45)`
- Beta auto: `v1.2.3-beta.2-auto(45)`
- Release assets: `OpenVPNGateClient_Phone_1.2.3(45).apk` (same for `.aab` and TV)

## Data and Privacy
- Server list: fetched from the configured primary endpoint with fallback to VPN Gate.
- Local storage: selected servers and last connection metadata are kept in shared preferences on the device.
- The app does not call `ipinfo.io` or similar IP geolocation services.
- No analytics or advertising SDKs are included. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for details.

## Licensing
This project, including the bundled `ics-openvpn` fork, is distributed under GPL-2.0-only. Review `LICENSE` and upstream notices in `src/external/OpenVPNEngine/doc/LICENSE.txt` before distributing on app stores. Contributions are accepted under the same license.

