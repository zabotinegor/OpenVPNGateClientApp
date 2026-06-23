# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> For the full operational rules used by all AI agents in this repo, read [AGENTS.md](AGENTS.md) first. CLAUDE.md summarizes the most important points and adds Claude Code–specific context.

## Build and Test

**Gradle root is `src/`, not the repo root.** Run all Gradle commands from there.

```bash
# Initialize submodules (required before any build touching resources or native code)
git submodule update --init --recursive

# Debug build (mobile + tv APKs)
.\gradlew.bat assembleDebugApp          # Windows
./gradlew assembleDebugApp              # Linux/Mac

# Unit tests
./gradlew testDebugUnitTestApp

# Instrumented tests (requires connected ADB device)
./gradlew connectedDebugAndroidTestApp   # mobile/core Espresso tests
./gradlew connectedDebugAndroidTestTv    # TV/Leanback Espresso tests

# Release APKs — all four -P params are required
./gradlew assembleReleaseApp \
  -PappVersionName=1.0.0 -PappVersionCode=1 \
  -PPRIMARY_SERVERS_URL=https://... -PFALLBACK_SERVERS_URL=https://...
```

### Required build properties

`PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` must be supplied or the build fails. Resolution order: Gradle `-P` flag → environment variable → `servers.local.json` (at repo root or `src/`, not committed).

```json
{ "PRIMARY_SERVERS_URL": "https://...", "FALLBACK_SERVERS_URL": "https://..." }
```

## Architecture

Two thin launcher modules (`src/mobile`, `src/tv`) share one `src/core` library that owns all business logic. The VPN engine lives in `src/openVpnEngine` → `src/external/OpenVPNEngine/main` (git submodule, branch `OpenVPNClientApp-integration`).

```
src/
  core/    ← business logic, VPN orchestration, all shared UI flows, DI
  mobile/  ← phone/tablet launcher (thin wrapper over core)
  tv/      ← Android TV launcher (thin wrapper over core)
  openVpnEngine → src/external/OpenVPNEngine/main  (submodule)
media/     ← app icon/banner assets (submodule)
```

**Keep new feature and domain logic in `src/core`.** Only put code in `mobile` or `tv` when it is genuinely launcher-specific.

### Key entry points

| File | Purpose |
|------|---------|
| [src/core/…/di/CoreDi.kt](src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/di/CoreDi.kt) | DI wiring (source of truth) |
| [src/core/…/ui/splash/SplashActivityCore.kt](src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashActivityCore.kt) | Shared startup/splash flow |
| [src/core/…/ui/splash/SplashServerPreloadInteractor.kt](src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashServerPreloadInteractor.kt) | Startup server preload |
| [src/core/…/ui/main/MainActivityCore.kt](src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainActivityCore.kt) | Shared main UI flow |
| [src/core/…/servers/ServerSelectionSyncCoordinator.kt](src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerSelectionSyncCoordinator.kt) | Server-list sync entry point (splash, main, settings, periodic) |
| [src/core/…/servers/refresh/ServerRefreshWorker.kt](src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/refresh/ServerRefreshWorker.kt) | Background periodic refresh |
| [src/core/…/vpn/OpenVpnService.kt](src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt) | VPN lifecycle integration |
| [src/core/…/vpn/ServerAutoSwitcher.kt](src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ServerAutoSwitcher.kt) | Auto-switch logic and hardprobe trigger (inactivity → probe enqueue) |
| [src/core/…/servers/sse/SseServerEventsClient.kt](src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt) | SSE client — foreground-only long-poll; triggers server sync on `servers-changed` push event |

## Conventions

- **Logging**: Timber only. Follow [src/docs/logging-policy.md](src/docs/logging-policy.md); never use `android.util.Log` in app code.
- **DI**: Koin. Wire through `CoreDi.kt`.
- **UI**: ViewBinding + Android native. Don't introduce new UI or DI patterns.
- **Endpoints**: Never hardcode production URLs in source. Use build properties → env → `servers.local.json`.
- **`app_name`**: Injected via Gradle `resValue`; don't duplicate in string resources.
- **Branch naming**: `feature/<name>`, `bugfix/<issue>`, `hotfix/<issue>`.

## Critical Pitfalls

- **Missing build properties** — `PRIMARY_SERVERS_URL` / `FALLBACK_SERVERS_URL` are required; missing values fail the build at configuration time.
- **Missing media assets** — `src/copy_drawables.gradle.kts` copies launcher icons from the `media` submodule before `preBuild`; build fails if files are absent.
- **Release hardening** — `isMinifyEnabled=true` and `isShrinkResources=true` in `mobile` and `tv` release variants are intentional; do not remove.
- **Engine submodule** — `src/external/OpenVPNEngine` is an upstream integration boundary. Avoid incidental edits there unless the task explicitly requires engine changes.
- **VPN permissions** — `src/core/src/main/AndroidManifest.xml` declares the VPN foreground service. Edit service, permission, or exported settings there with care.
- **jniLibs packaging** — Preserve each module's current `jniLibs.useLegacyPackaging` setting.
- **Shared application ID** — `mobile` and `tv` share the same package base intentionally (VPN permission/signing). Do not split without understanding the implications.

## Reference Docs

- [README.md](README.md) — prerequisites, signing, media assets, runtime behavior, release commands
- [AGENTS.md](AGENTS.md) — full agent/contributor operational rules
- [src/docs/logging-policy.md](src/docs/logging-policy.md) — logging levels, throttling, privacy rules
- [src/docs/server-sync-flow.md](src/docs/server-sync-flow.md) — server-list sync triggers, guard conditions, coordinator reuse
- [tests/manual-e2e/README.md](tests/manual-e2e/README.md) — manual E2E test structure
- [AGENTS.local.md](AGENTS.local.md) — machine-specific paths (not committed; ask user if absent)
