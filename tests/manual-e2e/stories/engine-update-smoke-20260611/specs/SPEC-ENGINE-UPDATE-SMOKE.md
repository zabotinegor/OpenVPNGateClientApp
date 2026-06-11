# Spec: Engine Update Smoke — fix/sync-agent-assets (2026-06-11)

## Context

Engine submodule updated from `736ebfe1` to `a83da9ff` on branch `fix/sync-agent-assets`:
- Merged 14 upstream commits from `schwabe/ics-openvpn` (OpenSSL 4.0, OpenVPN lib bump, SWIG API refactor)
- NDK pinned at `29.0.14206865`
- Integration-branch conflict: `build.gradle.kts` resolved; `GenerateSwigTask` / `outputs.cacheIf { true }` preserved
- Client fix: `VpnConfigurationTest` cipher assertion updated (`assertFalse`) for upstream compat-mode change

## Objective

Verify no regression introduced by the engine update across core app flows.

## Device

- Model: Samsung Galaxy A71 (SM_A715F)
- ADB serial: R58N849XQEY
- Android: 13 (One UI 5)
- APK: `src/mobile/build/outputs/apk/debug/mobile-debug.apk` (versionCode 63, built from `fix/sync-agent-assets`)

## Scope

| ID | Area | Regression risk |
|----|------|----------------|
| SMOKE-01 | Cold launch, splash → main transition | Engine init crash |
| SMOKE-02 | Server list load (DEFAULT_V2 cache) | Server parsing |
| SMOKE-03 | VPN connect + watchdog health + disconnect | Core tunnel |
| SMOKE-04 | Notification tap → MainActivity (US-11 regression) | Notification intent |
| SMOKE-05 | No fatal exceptions across full session | General stability |

## Pass criteria

- No `FATAL EXCEPTION` / `RuntimeException` in logcat
- `LEVEL_CONNECTED / CONNECTED` reached and watchdog healthy (traffic flowing)
- `DISCONNECTING → DISCONNECTED` clean on user stop
- `topResumedActivity = .mobile.MainActivity` after notification tap
