# Android dependency modernization and smoke-regression procedure

The Gradle root is `src/`. The app has **two** launchers (`mobile`, `tv`) over
`core`, with an external native/OpenVPN engine submodule and a separate media
submodule. An inventory is **not** evidence of a successful upgrade.

## Inventory and compatibility

From the repository root:

```bash
python3 scripts/ci/android_dependency_inventory.py --check --format json > android-dependencies.json
python3 -m unittest discover -s scripts/ci/tests -p 'test_android_dependency_inventory.py'
```

This read-only, sorted inventory captures the root version catalog and
version references, Gradle build references/settings, Gradle wrapper,
GitHub Actions pins/JDK/toolchain fields, and submodule configuration.
The parser does **not** resolve transitive Gradle components, consult CVE feeds,
read the separate engine repository, or attest installed SDK/NDK/runtime versions.

For each configuration, run actual dependency resolution and inspect direct
and **transitive** versions and rejected conflicts; examples from `src/`:

```bash
./gradlew :core:dependencies --configuration debugRuntimeClasspath
./gradlew :mobile:dependencies --configuration debugRuntimeClasspath
./gradlew :tv:dependencies --configuration debugRuntimeClasspath
./gradlew :core:dependencyInsight --configuration debugRuntimeClasspath --dependency okhttp
```

Also inspect the engine module (including its own Kotlin, OpenVPN, native
dependencies and licensing), R8/ProGuard behavior, Kotlin metadata, APK ABI
packaging, AndroidX minimum compileSdk, and release dependency advisories.
Record a table per component: declared/resolved/deployed version, supported
candidate, official evidence, security risk, breaking change, test, rollback
and explicit disposition (updated, deferred, blocked). Do not silently
replace stable versions with alpha/beta or claim that the catalog is the
complete installed dependency graph.

**Compatibility boundary:** AGP 8.13.2 uses Gradle 8.13 and JDK 17,
but its documented maximum supported platform is API 36.1. The external
engine currently requires API 37. AGP 9.1+ adds Android API 37 support
and changes Kotlin plugin defaults (built-in Kotlin), Gradle and potentially
variant DSL/R8 rules. Validate this as a coordinated migration, not an
independent catalog edit. The engine pin belongs to
`OpenVPNClientApp-integration`; use the canonical update-engine workflow
if a separate engine change becomes necessary.

## Automated build and test evidence

Initialize both submodules before running resource or native builds. Provide
valid build-time PRIMARY_SERVERS_URL and FALLBACK_SERVERS_URL using existing
local, environment or Gradle property mechanisms. Do not commit endpoints,
signing secrets or local environment files.

From `src/`, on JDK 17 with the needed SDK/NDK/CMake/SWIG installed:

```bash
./gradlew --no-daemon testDebugUnitTestApp
./gradlew --no-daemon assembleDebugApp
./gradlew --no-daemon assembleReleaseApp
./gradlew --no-daemon bundleReleaseApp
./gradlew --no-daemon :openVpnEngine:testFullDebugUnitTest
```

Run Android lint and target-specific test suites in the canonical Developer
Flow as applicable. Inspect release R8/mapping rules and compare mobile/TV
APK size, native ABIs, signature/manifest and version metadata against baseline.
Verify GitHub Actions and Azure DevOps fallback pipeline parity without
activating actual signing or release publishing. Run the local test suite for
the inventory script; do not count it as Android runtime regression coverage.

## Device smoke matrix — execute on builds of the upgraded branch

Record **PASS / FAIL / BLOCKED / NOT RUN** with build SHA, SDK/device type,
timestamp, steps, observed result and non-secret logs for every applicable row.

| Scenario | Mobile/tablet | Android TV | Pass evidence |
| --- | --- | --- | --- |
| Fresh install and cold launch | Required | Required | Splash→main, no crash/ANR |
| Server list and source fallback | Required | Required | Country/server list; fallback when primary fails |
| Favorites and selection persistence | Required | Required | Correct identity across restart and locale changes |
| VPN permission and connect | Required | Required | Tunnel connected with traffic |
| Watchdog, stop and clean disconnect | Required | Required | State progression, no orphaned tunnel |
| Pause/resume, background/foreground | Required | Required | Preserved session; correct controls |
| Reconnect/auto-switch | Required | Required | Stable lifecycle and reconnect bounds |
| Notification tap and stop action | Required | Required | Opens correct activity; no crash, no stale icon |
| DNS and per-app VPN filter | Required | When supported | Actual tunnel settings and allowed/blocked apps |
| Update check and version metadata | Required | Required | Compatible server response, correct variant |
| Settings, theme, localization | Required | Required | No loss/reset across restart |
| Release packaging and orientation | Required | Required | Minify and shrink retained, D-pad focus on TV |

Follow existing canonical device instructions in
`docs/operations/device-qa-phone.md`,
`docs/operations/device-qa-tv.md`,
`docs/guides/engine-update.md`, and
`tests/manual-e2e/environment/android-adb-vpn-qa-runbook.md`.
Include ADB/logcat evidence and test against at least one physical device or
suitable emulator for each required platform; an ordinary phone emulator
does not substitute for Leanback TV. Do not infer a PASS from a successful
Gradle build.

## Pipeline, release and rollback gates

Audit all workflows under `.github/workflows/` and the active Azure DevOps
fallback implementation, especially checkout with recursive submodules,
JDK, Gradle cache, Android SDK 36/37 acquisition, NDK 29, CMake 3.22.1,
SWIG, signing secrets and versionCode/tag behavior. Coordinate with any
in-progress CI provider parity work before editing shared pipeline logic.

Preserve `src/core` runtime API semantics, VPN foreground-service permissions,
native library packaging, release minification/resource shrinking and the
app-specific engine force-stop patch. Before publication require the complete
device matrix, Gradle validation, deploy pipeline checks, downgrade/recovery
plan and explicit owner release authorization. No build signing or upload is
authorized by this audit.
