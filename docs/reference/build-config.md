# Build configuration

SDK levels, build fields, and how backend URLs are resolved. Run all Gradle commands from `src/`.

## SDK levels

| Module | compileSdk | minSdk | targetSdk |
|---|---|---|---|
| `core` | 36 | 24 | — (library module, not set) |
| `mobile` | 36 | 24 | 36 |
| `tv` | 36 | 24 | 36 |
| **`:openVpnEngine`** (submodule) | **37** | 23 | **37** |

All three app modules are aligned at compileSdk/targetSdk 36 and minSdk 24. Only the engine differs.

> **The engine compiles against a higher SDK than the app.** A clean build on a machine without SDK
> Platform 37 fails with `Failed to find target with hash string 'android-37'`. Install the platform
> and retry — it is not a code problem. Any statement that this project needs only "SDK 24+ (target
> 36)" is describing the app modules and omits the engine.

JVM target 11. Core library desugaring (`desugar_jdk_libs`, `isCoreLibraryDesugaringEnabled = true`) is enabled in `core`, `mobile` and `tv` so `java.time` works on API 24–25; see [operations/dependency-modernization.md](../operations/dependency-modernization.md).

## Backend URL resolution

`PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` are **required**; a missing value fails the build.
`src/core/build.gradle.kts` resolves each in this order:

1. Gradle property (`-PPRIMARY_SERVERS_URL=...`)
2. environment variable
3. `servers.local.json` — **`src/servers.local.json` first, repo root second**

> **The `src/` copy wins.** `src/settings.gradle.kts` makes `src/` the Gradle root, so
> `loadLocalServersConfig()` probes `rootProject.file("servers.local.json")` — that is
> `src/servers.local.json` — before `rootProject.rootDir.parentFile`, the repository root. It takes
> the **first file that exists** and never merges the two. If both are present, edits to the
> repo-root copy have no effect and the build keeps using the endpoints in `src/`, silently. Delete
> the `src/` copy rather than trying to override it.

For production builds, use `PRIMARY_SERVERS_URL=https://openvpngateclient.com`.
The GitHub repository variable `PRIMARY_SERVERS_URL` supplies the app's public API origin;
`VERSIONS_API_BASE_URL` is a separate repository variable used by release workflows to publish
version metadata and must also use the canonical production domain. Changes to GitHub variables
are operational settings changes, not source-code changes. Keep `FALLBACK_SERVERS_URL` pointing
to the independent VPN Gate CSV service.

### Canonical-origin safeguards

The backend runs on Azure with a RackNerd standby, and DNS failover happens behind the canonical
domain, so the client never needs to know which site is active. The retired public hostname
`openvpngateclient.azurewebsites.net` is rejected where it would reach users:

- **Gradle:** `src/core/build.gradle.kts` fails any build whose requested tasks include a release
  variant when `PRIMARY_SERVERS_URL` resolves to the retired host. Debug and test builds are not
  restricted, so local and QA endpoints keep working.
- **Release workflows** (`release-by-dev`, `release-by-main`, `release-by-tag`): the step
  `Validate production endpoint configuration` runs `.github/ci/validate-production-endpoints.sh`
  in strict mode before any build. It requires `PRIMARY_SERVERS_URL` and `VERSIONS_API_BASE_URL` to
  be HTTPS, credential-free, path-free and on the canonical host, requires both to share one
  origin, and requires `FALLBACK_SERVERS_URL` to stay off the canonical and retired hosts.
- **Artifact check:** the step `Verify release artifacts embed the canonical API origin` runs
  `.github/ci/verify-artifact-endpoints.sh` over the staged APKs and AABs, before tagging or
  publishing, and fails when the origin is missing or the retired host is present. The dex scan
  alone cannot tell the API field from other occurrences of the same URL (for example
  `AboutMeta.WEBSITE`), so the same step also runs `.github/ci/inspect-buildconfig-origin.py`, which
  requires the generated release `core/BuildConfig.java` (under
  `src/core/build/generated/source/buildConfig/release`) to declare `PRIMARY_SERVERS_URL` exactly
  once with the expected origin. It fails closed when the file is missing or ambiguous.
- **Pull request builds** run the same validation in `warn` mode and run the script regression
  tests (`.github/ci/tests/ci-endpoint-scripts.test.sh`). `scripts/ci/tests/test_release_workflow_contracts.py`
  keeps the workflow wiring from drifting.

The scripts only read and print the public URL variables. `VERSIONS_API_KEY` is set at job level in
the release workflows, so the scripts inherit it in their environment, but they never read or print
it, and they do not touch signing material. Backend URLs must be a bare `https://` origin (no port,
credentials, path, query or unusual characters); the fallback must not be on the canonical domain or
any subdomain of it. Integration builds (`ci-integration`) have no validator step; they are covered
only by the Gradle guard.

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
