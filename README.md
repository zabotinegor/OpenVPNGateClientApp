# Client for OpenVPN Gate

Open-source Android client for connecting to the public VPN Gate network. The app is built on top of the `ics-openvpn` engine (GPLv2) and ships mobile and TV variants that share a common core UI/logic module.

- Homepage: https://openvpngateclient.azurewebsites.net
- GitHub (app): https://github.com/zabotinegor/OpenVPNGateClientApp
- GitHub (engine submodule): https://github.com/zabotinegor/OpenVPNGateClientEngine
- Privacy Policy: https://openvpngateclient.azurewebsites.net/privacy-policy (copy in [PRIVACY_POLICY.md](PRIVACY_POLICY.md))
- Terms of Use / Disclaimer: https://openvpngateclient.azurewebsites.net/terms-of-use (copy in [TERMS.md](TERMS.md))
- Google Play: internal testing planned (AAB builds ready; link will appear after publish)
- License: [GPL-2.0-only](LICENSE)

## Features
- OpenVPN-based client with the `ics-openvpn` engine (bundled as the `openVpnEngine` submodule)
- Server catalog pulled from a primary endpoint with automatic fallback to VPN Gate public feed
- Manual country selection with basic connection stats (speed, duration, IP, status)
- Separate mobile and TV launchers sharing the same core UI and networking code

## Stack and Modules
- Kotlin, Android SDK 24+, ViewBinding, Retrofit/OkHttp
- Modules:
  - `mobile` — phone/tablet app
  - `tv` — TV flavor
  - `core` — shared UI, networking, and VPN orchestration
  - `external/OpenVPNEngine` — fork of `ics-openvpn` (GPLv2)
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
  ./gradlew :mobile:assembleDebug
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

## CI/CD
GitHub Actions workflows are located in `.github/workflows` and build signed release artifacts from `dev`, `main`, and tags (requires secrets for signing and server URLs). Dev builds now also produce `.aab` bundles for Play internal testing.

## Data and Privacy
- Server list: fetched from the configured primary endpoint with fallback to VPN Gate.
- Public IP info: fetched from `https://ipinfo.io/json` to display IP and city.
- Local storage: selected servers and last connection metadata are kept in shared preferences on the device.
- No analytics or advertising SDKs are included. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for details.

## Licensing
This project, including the bundled `ics-openvpn` fork, is distributed under GPL-2.0-only. Review `LICENSE` and upstream notices in `src/external/OpenVPNEngine/doc/LICENSE.txt` before distributing on app stores. Contributions are accepted under the same license.
