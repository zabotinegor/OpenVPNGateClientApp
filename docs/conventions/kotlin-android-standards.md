# Kotlin and Android standards

Rules for writing code in this repository. Android/Kotlin only — this is not a backend service.

## Module boundaries

- **`src/core` owns almost all business logic**: VPN orchestration, repositories, settings,
  networking, logging and shared UI flows.
- **`src/mobile` and `src/tv` stay thin.** They are launchers. Keep feature logic out of them unless
  it is genuinely launcher-specific — TV `MainActivity` carries D-pad focus handling because that is
  launcher-specific; a repository does not belong there.
- The shared `applicationId` and package base between mobile and tv are **intentional**. Do not split
  package names without understanding the VPN-permission and signing implications.

## Dependency injection

Koin. `core/di/CoreDi.kt` is the source of truth for wiring — read it before adding a dependency
rather than constructing objects ad hoc.

## UI

ViewBinding, Kotlin-based modules. Match the existing style; do not introduce a second UI or DI
pattern alongside it.

## Logging

Timber only. **Do not introduce `android.util.Log` in app code.** Levels, release behaviour,
anti-spam throttling and privacy rules are in
[../features/logging.md](../features/logging.md).

Never log secrets, raw credentials, or full sensitive URLs.

## Endpoints and configuration

Build-time server endpoints come from Gradle properties, then environment variables, then
`servers.local.json` — in that order. **Do not hardcode production endpoints in source.**

`PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` are required; a missing value fails the build.
Routes are derived from the primary base at runtime rather than written out individually. See
[../reference/build-config.md](../reference/build-config.md).

> Scope note: this rule is about **API endpoints**. `AboutMeta.kt` intentionally hardcodes the two
> public website links (privacy policy, terms) because they are user-facing destinations, not
> service endpoints the client negotiates with.

## Resources

`app_name` is injected via Gradle `resValue`. Do not duplicate it into shared string resources
unless the build logic changes.

## Cross-repo API contracts

If a change touches API contracts for updates, releases, version metadata or server-list payloads,
inspect the backend implementation (local path is in `AGENTS.local.md`) and keep the client and
server formats aligned. The endpoints this app actually calls are listed in
[../reference/api-endpoints.md](../reference/api-endpoints.md).

## Branch naming

`feature/<name>`, `bugfix/<issue>`, `hotfix/<issue>`. Lowercase, hyphens for multi-word names.

## Release hardening — do not "clean up"

- `src/mobile` and `src/tv` release builds must keep `isMinifyEnabled = true` and
  `isShrinkResources = true`.
- Preserve each module's current `jniLibs.useLegacyPackaging` setting.

Both look like stale configuration and are not.

*Last verified against: `src/core/build.gradle.kts`, `src/mobile/build.gradle.kts`, `src/tv/build.gradle.kts`, `core/di/CoreDi.kt` (2026-07-31).*
