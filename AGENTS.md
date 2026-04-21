# Project Guidelines

## Project Overview
- This repository contains an Android VPN client for VPN Gate and compatible server lists.
- The Gradle root is `src/`, not the repository root.
- The app ships as two launcher apps, `src/mobile` and `src/tv`, over one shared logic module, `src/core`.
- `src/openVpnEngine` points to `src/external/OpenVPNEngine/main`, which is an external engine submodule and should be treated as a high-risk integration boundary.
- The related backend/API codebase is local-only and must be resolved from untracked `AGENTS.local.md` at repo root.
- If `AGENTS.local.md` is missing, ask the user for the local backend path and do not add it to tracked files.

## Build and Test
- Run Gradle commands from `src/`.
- Prefer the aggregate tasks defined in `src/build.gradle.kts`:
  - `./gradlew assembleDebugApp`
  - `./gradlew testDebugUnitTestApp`
  - `./gradlew assembleReleaseApp -PappVersionName=... -PappVersionCode=... -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...`
  - `./gradlew bundleReleaseApp -PappVersionName=... -PappVersionCode=... -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...`
- Signed release builds need `src/keystore.properties` and the referenced keystore file. Local release builds may be produced unsigned when this file is absent.
- Before any build that touches resources or native code, initialize submodules: `git submodule update --init --recursive`.

## Architecture
- `src/core` owns almost all business logic: VPN orchestration, repositories, settings, networking, logging, and shared UI flows.
- `src/mobile` and `src/tv` should stay thin. Keep feature logic out of these modules unless it is launcher-specific.
- Koin is the DI container. Use `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/di/CoreDi.kt` as the source of truth for wiring.
- The shared application ID and package base are intentional. Do not split package names between mobile and tv without understanding VPN permission and signing implications.

## Conventions
- Branch naming follows `feature/<feature_name>` for feature branches, `bugfix/<issue>` for bug fixes, and `hotfix/<issue>` for urgent hotfixes. Use lowercase with hyphens for multi-word features.
- Keep new domain and UI logic in `src/core` unless the change is genuinely mobile-only or tv-only.
- Use Timber for logging. Follow `src/docs/logging-policy.md`; do not introduce `android.util.Log` for app code.
- Do not log secrets, raw credentials, or full sensitive URLs.
- Build-time server endpoints come from Gradle properties, environment variables, or `servers.local.json`, in that order. Do not hardcode production endpoints in source files.
- If a task changes API contracts for updates, releases, version metadata, or server-list payloads, inspect the backend implementation using the local path from `AGENTS.local.md` and keep client/server formats aligned.
- `app_name` is injected via Gradle `resValue`; do not duplicate it in shared string resources unless the build logic changes.
- This project uses ViewBinding and Kotlin-based Android modules. Match the existing style instead of introducing a new UI or DI pattern.

## OpenVPN Engine Update Workflow (Local AI Agents)
- Context:
  - Engine repository: `https://github.com/zabotinegor/OpenVPNGateClientEngine`.
  - Engine fork source: `schwabe/ics-openvpn`.
  - Integration intent: keep the engine changes minimal and preserve the library shape used by this app.
  - Submodule declaration and target branch are defined in `.gitmodules`.
- Required update flow:
  1. Synchronize upstream branch from `schwabe/ics-openvpn` into `OpenVPNGateClientEngine` main.
  2. Merge `main` into `OpenVPNClientApp-integration` branch in the engine repository.
  3. Resolve conflicts minimally, preserving this repository's engine-as-library behavior.
  4. In this client repository, initialize submodules and run app validation builds/tests from `src/`.
  5. Update integration branches used by the app and update the active feature branch as needed.
  6. Refresh markdown documentation when behavior, process, or constraints change.
- Validation baseline after engine update:
  - `./gradlew assembleDebugApp`
  - `./gradlew testDebugUnitTestApp`
  - For release verification, use `assembleReleaseApp` or `bundleReleaseApp` with required `-P` properties.
- Safety constraints:
  - Do not perform incidental refactors in `src/external/OpenVPNEngine` during conflict resolution.
  - Keep module wiring intact: `:openVpnEngine` must continue to map to `src/external/OpenVPNEngine/main`.

## Project-Specific Pitfalls
- `PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` are required for builds through `src/core/build.gradle.kts`. Missing values fail the build.
- `src/copy_drawables.gradle.kts` copies required launcher assets from the `media` submodule. If the expected files are missing, builds fail before packaging.
- `src/core/src/main/AndroidManifest.xml` contains the VPN service declaration for Android special-use foreground services. Be careful when editing service, permission, or exported settings there.
- `src/external/OpenVPNEngine` is an upstream integration area. Avoid incidental edits there unless the task explicitly requires engine changes.
- Release build hardening is intentional: `src/mobile` `release` and `src/tv` `release` must keep `isMinifyEnabled=true` and `isShrinkResources=true`.
- Preserve each module's current `jniLibs.useLegacyPackaging` setting; do not change these as cleanup without a concrete need.

## Agent Documentation Governance
- Keep AI-agent governance docs synchronized when workflow instructions change:
  - .github/AGENTS-REGISTRY.md
  - .github/FRONTMATTER-SCHEMA.md
- Keep local overlays aligned with global docs while preserving local-only constraints:
  - README.local.md
  - AGENTS.local.md
- For docs-only maintenance tasks, follow .github/agents/docs-maintainer.agent.md and .github/skills/docs-maintenance/SKILL.md.

## Docs to Link Instead of Rewriting
- `README.md` for repository layout, prerequisites, signing, media assets, runtime behavior, and release commands.
- `src/docs/logging-policy.md` for logging levels, throttling, and privacy rules.
- `PRIVACY_POLICY.md` and `TERMS.md` for user-facing policy text.
- `LICENSE` and `src/external/OpenVPNEngine/doc/LICENSE.txt` for redistribution and licensing context.

## Useful Starting Points
- `src/build.gradle.kts` for aggregate app tasks.
- `src/core/build.gradle.kts` for required build configuration and generated `BuildConfig` fields.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/di/CoreDi.kt` for DI wiring.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashActivityCore.kt` for the shared splash/startup flow.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashServerPreloadInteractor.kt` for startup preload behavior.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainActivityCore.kt` for the shared main UI flow.
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt` for VPN lifecycle integration.

## When Extending Instructions
- If the repository later needs module-specific guidance, add nested `AGENTS.md` files under `src/core`, `src/mobile`, `src/tv`, or `src/external/OpenVPNEngine` instead of overloading this root file.
