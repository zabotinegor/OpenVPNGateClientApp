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

## Modernization record

Toolchain stays on AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21 and JDK 17.

| Component | Before | After | Disposition |
|---|---|---|---|
| AndroidX appcompat, activity, fragment, lifecycle, annotation, startup, webkit, test-core | 1.7.1 / 1.11.0 / 1.8.9 / 2.9.3 / 1.9.1 / 1.1.1 / 1.14.0 / 1.5.0 | 1.8.0 / 1.13.0 / 1.9.1 / 2.11.0 / 1.11.0 / 1.2.0 / 1.18.0 / 1.7.0 | Updated |
| core-ktx | 1.17.0 | 1.18.0 | Updated; 1.19 needs AGP 9.1+ and compileSdk 37 |
| Material, Leanback, WorkManager, gif-drawable | 1.13.0 / 1.0.0 / 2.10.5 / 1.2.29 | 1.14.0 / 1.2.0 / 2.12.0 / 1.2.32 | Updated |
| OkHttp, Retrofit, Glide | 4.10.0 / 2.9.0 / 4.11.0 | 4.12.0 / 2.11.0 / 4.16.0 | Updated within the current major |
| Coroutines, CommonMark, BouncyCastle, Mockito | 1.8.1 / 0.22.0 / 1.69 / 4.8.1 | 1.10.2 / 0.32.0 / 1.70 / 5.24.0 | Updated |
| Robolectric | 4.10.2 | 4.10.2 | Deferred: 4.17 drops `@Config(packageName)`, resolves no `manifest` path, and the controller-notification tests depend on an incidental `NoSuchMethodError` that no longer occurs |
| Kotlin | 2.0.21 | 2.0.21 | Deferred: the engine build script uses `kotlinOptions`, an error from Kotlin 2.2 |
| OkHttp 5, Retrofit 3, Glide 5, Koin 4 | — | — | Deferred: major migrations, not required for the toolchain |
| AGP, Gradle | 8.13.2 / 8.13 | unchanged | Blocked, see below |

**AGP 9 is blocked on the engine.** A trial with AGP 9.5.0, Gradle 9.6.1,
Kotlin 2.1.21 and `android.builtInKotlin=false` / `android.newDsl=false`
configured the app modules (`resValues = true` was needed per module for
`app_name`) but failed in the engine build script: `defaultConfig.targetSdk`
no longer exists for library modules, and `kotlinOptions` is deprecated. The
engine is owned by `OpenVPNClientApp-integration`; moving to AGP 9 needs a
separate engine change through the update-engine workflow. AGP 10 removes the
`newDsl` opt-out.

Evidence on this branch (Windows, JDK 17):

- `testDebugUnitTestApp`, `assembleDebugApp`, `assembleReleaseApp` and
  `bundleReleaseApp`: pass.
- `:openVpnEngine:testFullDebugUnitTest`: 2 of 48 fail
  (`TestConfigParser.cleanReImport`, `TestRestrictions.testOpenVPN3Restriction`);
  the same two fail on the pre-upgrade catalog.
- `lintDebug`: fails on existing findings (`NewApi` on `java.time` with minSdk 24:
  52 errors in `core`, 1 in `mobile`); the `tv` lint analysis crashes inside lint.
  There is no lint baseline in the repository.
- Not run yet: resolved dependency graphs, advisory review, pipeline parity
  audit, device smoke.

On Windows, Git checks out the asio submodule symlink `asio/asio/include` as a
text file, so the native build fails with `asio.hpp` not found. Replace it with
a junction to `asio/include` locally; do not commit that change.

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
