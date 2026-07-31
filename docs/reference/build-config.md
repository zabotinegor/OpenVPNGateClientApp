# Build configuration

SDK levels, build fields, and how backend URLs are resolved. Run all Gradle commands from `src/`.

## SDK levels

| Module | compileSdk | minSdk | targetSdk |
|---|---|---|---|
| `core` | 36 | 24 | 36 |
| `mobile` | 36 | 16* | 35 |
| `tv` | 32 | 16* | 31 |
| **`:openVpnEngine`** (submodule) | **37** | 23 | **37** |

\* launcher modules inherit the effective minimum from `core`.

> **The engine compiles against a higher SDK than the app.** A clean build on a machine without SDK
> Platform 37 fails with `Failed to find target with hash string 'android-37'`. Install the platform
> and retry — it is not a code problem. Any statement that this project needs only "SDK 24+ (target
> 36)" is describing the app modules and omits the engine.

JVM target 11.

## Backend URL resolution

`PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` are **required**; a missing value fails the build.
`src/core/build.gradle.kts` resolves each in this order:

1. Gradle property (`-PPRIMARY_SERVERS_URL=...`)
2. environment variable
3. `servers.local.json` (repo root, then `src/`)

Both must be **HTTPS**, and a host of `placeholder` is rejected. The resolved values are emitted as
`BuildConfig` fields; routes are derived from the primary base at runtime by `PrimaryDomainRoutes`,
not written out individually. See [api-endpoints.md](api-endpoints.md).

## Build fields

| Field | Values | Effect |
|---|---|---|
| `PRIMARY_SERVERS_URL` | HTTPS base | trusted backend; all API routes derive from it |
| `FALLBACK_SERVERS_URL` | HTTPS base | VPN Gate CSV; never trusted as an update host |
| `APP_RELEASE_TYPE` | `release` \| `beta` | sent as `releaseType` on update-check queries |

## Gradle tasks

Prefer the aggregates in `src/build.gradle.kts`:

```bash
./gradlew assembleDebugApp
./gradlew testDebugUnitTestApp
./gradlew connectedDebugAndroidTestApp        # phone, needs a connected device
./gradlew connectedDebugAndroidTestTv         # Leanback target
./gradlew assembleReleaseApp -PappVersionName=... -PappVersionCode=... -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...
./gradlew bundleReleaseApp   -PappVersionName=... -PappVersionCode=... -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=...
```

`testDebugUnitTestApp` does **not** cover the engine — see
[../conventions/testing-guidelines.md](../conventions/testing-guidelines.md).

## Before building

```bash
git submodule update --init --recursive
```

`src/copy_drawables.gradle.kts` copies launcher assets from the `media` submodule; missing files fail
the build before packaging.

## Signing

Signed release builds need `src/keystore.properties` and the referenced keystore. Local release
builds are produced unsigned when it is absent.

## Release hardening

`mobile` and `tv` release builds keep `isMinifyEnabled = true` and `isShrinkResources = true`;
`core` release has minify off. Each module's `jniLibs.useLegacyPackaging` setting is deliberate.
None of these are cleanup candidates.

*Last verified against: `src/core/build.gradle.kts`, `src/mobile/build.gradle.kts`, `src/tv/build.gradle.kts`, `src/external/OpenVPNEngine/main/build.gradle.kts`, `src/build.gradle.kts` (2026-07-31).*
