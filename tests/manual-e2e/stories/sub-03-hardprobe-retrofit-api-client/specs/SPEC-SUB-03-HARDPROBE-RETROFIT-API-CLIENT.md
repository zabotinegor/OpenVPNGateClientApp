# SPEC-SUB-03: Hardprobe Retrofit API Client — Manual QA Spec

## Story reference
- Story ID: SUB-03
- Story path: docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-03-hardprobe-retrofit-api-client.md
- Branch: feature/SUB-03-hardprobe-retrofit-api-client
- Device: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)

## What was changed
- New `ProbeResult` sealed class (`src/core/.../servers/probe/ProbeResult.kt`)
- New `HardProbeApiClient` wrapping `ProbeApi` (`src/core/.../servers/probe/HardProbeApiClient.kt`)
- `CoreDi.kt` — added `single { HardProbeApiClient(get()) }` Koin binding
- `okhttp-mockwebserver` added as test dependency only
- `gradle.properties` JVM heap reduced to 2048m

## QA scope
This sub-plan introduces no new UI surfaces. The Android QA surface covers:
1. DI graph initialization — Koin must resolve `HardProbeApiClient` without error at startup
2. App launch — no crash, no fatal exception
3. Normal server sync flow — no regression in existing server-list/splash behavior

## Out of scope
- Backend endpoint `POST /api/v2/servers/{id}/probe` (server-side unchanged)
- WorkManager retry queue (SUB-02)
- VPN inactivity trigger (SUB-04)
- Web / API / DB surfaces (Android-only client app)

## Test cases
- MQ-SUB03-001: App installs cleanly
- MQ-SUB03-002: App launches to main screen without crash
- MQ-SUB03-003: No DI-related fatal exception in logcat
- MQ-SUB03-004: Normal server sync log markers present (no regression)
