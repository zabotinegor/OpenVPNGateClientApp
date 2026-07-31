# OpenVPN engine submodule

The VPN engine is a **git submodule**, not a dependency you can bump in a version catalogue.

| | |
|---|---|
| Repository | `https://github.com/zabotinegor/OpenVPNGateClientEngine` |
| Fork of | `schwabe/ics-openvpn` (GPL-2.0-only) |
| Branch | `OpenVPNClientApp-integration` |
| Path | `src/external/OpenVPNEngine`, mapped to Gradle module `:openVpnEngine` |

Integration intent: keep engine changes **minimal** and preserve the library shape this app consumes.

## Update flow

1. Sync upstream `schwabe/ics-openvpn` into `OpenVPNGateClientEngine` `main`.
2. Merge `main` into `OpenVPNClientApp-integration` in the engine repository.
3. Resolve conflicts **minimally**, preserving engine-as-library behaviour.
4. In this repository, `git submodule update --init --recursive`, then build and test from `src/`.
5. Update the integration branch reference and the active feature branch.
6. Refresh documentation where behaviour, process or constraints changed.

## Validation after a bump

```bash
./gradlew assembleDebugApp
./gradlew testDebugUnitTestApp
./gradlew :openVpnEngine:testFullDebugUnitTest    # the app task does NOT cover the engine
```

Then run the full regression checklist in [../guides/engine-update.md](../guides/engine-update.md) —
cold launch, server-list load, connect/watchdog/disconnect, notification-tap regression, full-session
stability — before trusting the merge.

**SDK level.** The engine module compiles against a **higher `compileSdk` than the app modules**. If
upstream raises it, the first build on a machine without that SDK Platform fails with
`Failed to find target with hash string 'android-NN'`. Install the platform and retry; it is not a
code problem. Current levels are in [../reference/build-config.md](../reference/build-config.md).

## Hard constraints

- **No incidental refactors** inside `src/external/OpenVPNEngine` during conflict resolution.
- Module wiring must stay: `:openVpnEngine` → `src/external/OpenVPNEngine/main`.
- Treat the engine as an upstream integration area — avoid edits there unless the task explicitly
  requires engine changes.

## Where the boundary is

Six production files in `core` import `de.blinkt.openvpn.*`, and the useful rule is not a file count:

- **`core/vpn/OpenVpnService.kt` owns the whole service/lifecycle surface** — profiles, config
  parsing, AIDL, launch, status listeners. Any of those types imported elsewhere is a violation.
- **`ConnectionStatus` is an accepted seam**, published via `ConnectionStateManager.engineLevel` and
  consumed by `ConnectionState.kt`, `ServerAutoSwitcher.kt` and the two connection-control UI
  components, which need finer granularity than the app's own 6-value enum.
- **`mobile` and `tv` import nothing from the engine.** That boundary is clean and must stay clean.

Judge a new engine import against those categories, not against "only one file may import it".
The full breakdown, with why each seam exists, plus the two-process/AIDL model, is in
[../features/vpn-connection.md](../features/vpn-connection.md).

*Last verified against: `.gitmodules`, `src/settings.gradle.kts`, `src/core/build.gradle.kts` (2026-07-31).*
