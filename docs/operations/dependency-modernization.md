# Android dependency modernization and smoke-regression procedure

The Gradle root is `src/`. The app has **two** launchers (`mobile`, `tv`) over
`core`, with an external native/OpenVPN engine submodule and a separate media
submodule. An inventory is **not** evidence of a successful upgrade.

## Index

- [Status and scope](#status-and-scope)
- [Inventory and compatibility](#inventory-and-compatibility)
- [Toolchain compatibility boundary](#toolchain-compatibility-boundary)
- [A-AC1: dependency and toolchain review](#a-ac1-dependency-and-toolchain-review)
- [A-AC2: OpenVPN Engine 0.7.69 integration](#a-ac2-openvpn-engine-0769-integration)
- [Build and test evidence](#build-and-test-evidence)
- [CI and release pipelines (GitHub Actions)](#ci-and-release-pipelines-github-actions)
- [A-AC6: upgrade and rollback per area](#a-ac6-upgrade-and-rollback-per-area)
- [Regression checklist](#regression-checklist)
- [Device smoke matrix](#device-smoke-matrix)
- [Smoke run record](#smoke-run-record)
- [Pipeline, release and rollback gates (CI Gate)](#pipeline-release-and-rollback-gates-ci-gate)

## Status and scope

This document records the dependency and toolchain review for the
`feature/release/09.11.2026` release. It separates three kinds of statement:

- **Verified** — checked against an official source or a repository file in the
  release diff, with the source named in the table.
- **Recorded** — taken from an earlier run on this branch (test output, OSV
  lookup, device session). It was not re-executed for this revision.
- **Unverified / open** — not established. Do not rely on these until checked.

Nothing here claims that the catalog is the complete installed dependency graph.
The device matrix below is not complete; see the smoke record for what was and
was not executed.

## Inventory and compatibility

From the repository root:

```bash
python3 scripts/ci/android_dependency_inventory.py --check --format json > android-dependencies.json
python3 -m unittest discover -s scripts/ci/tests -p 'test_android_dependency_inventory.py'
```

Requires **Python 3.11+** (the script imports `tomllib`). On Windows, use `python` if `python3` is not on `PATH`.

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
./gradlew :core:dependencyInsight --configuration debugRuntimeClasspath --dependency okio
```

The `okio` insight is **not yet run** for this review. The resolved Okio version
is therefore unknown here (see the CVE-2023-3635 row in the A-AC1 table).

## Toolchain compatibility boundary

Verified against the AGP release notes (see A-AC1 sources):

- AGP 8.13 supports a maximum of **API 36.1**, requires **Gradle 8.13** and **JDK 17**.
  This is the currently deployed toolchain (`AGP 8.13.2`, Gradle wrapper 8.13).
- AGP 9.5 supports a maximum of **API 37**, requires **Gradle 9.6.0** and **JDK 17**.
- The external engine module targets `compileSdk`/`targetSdk` 37 (engine repository,
  `OpenVPNClientApp-integration`). The app on AGP 8.13 therefore builds an engine
  that targets a newer API than AGP 8.13 documents as supported. The build works in
  the recorded run; this combination should not be extended without re-verification.

Not established in this revision: the exact AGP 9.0 and 9.1.x requirements
(minimum Kotlin plugin, default built-in Kotlin, R8 behavior). An earlier pass
recorded AGP 9.1.1 as supporting API 37.0 with Gradle 9.3.1 and KGP 2.2.10; that
row was not re-fetched here and must be re-checked before any AGP 9 change.

AGP 9 is a coordinated migration (Gradle, Kotlin plugin defaults, variant DSL,
R8), not an independent catalog edit. The engine pin belongs to
`OpenVPNClientApp-integration`; use the `update-engine` workflow if an engine
change becomes necessary.

## A-AC1: dependency and toolchain review

Versions below are taken from the release diff (`62924f0` for the catalog and
Gradle files, `21474a5` for the engine gitlink). "Direct" means declared in
`src/gradle/libs.versions.toml` or a module build script; "transitive" means
resolved through another dependency and not declared by the app.

Disposition values: **updated** (changed in this release), **deferred** (not
changed, reason given), **blocked** (cannot change until a named prerequisite).

| Component | Class | Before → after | Disposition | Reason / risk | Source (what was verified) |
|---|---|---|---|---|---|
| Android Gradle Plugin | direct (build) | 8.13.2 → 8.13.2 | blocked | AGP 9 requires the engine and Gradle migration; trial failed in the engine build script (see below) | [AGP release notes](https://developer.android.com/build/releases/gradle-plugin) (9.5 page verified: API 37, Gradle 9.6.0, JDK 17; 8.13 row verified on its release-notes page: API 36.1, Gradle 8.13, JDK 17) |
| Gradle wrapper | direct (build) | 8.13 → 8.13 | blocked | Follows AGP | Same AGP page; repository `gradle-wrapper.properties` |
| Kotlin | direct (build) | 2.0.21 → 2.0.21 | deferred | Engine build script uses `kotlinOptions`, which is deprecated; the removal behavior at Kotlin 2.2 was **not verified** in this revision | Engine `main/build.gradle.kts:87` at its HEAD; Kotlin deprecation status not re-fetched |
| AndroidX appcompat | direct | 1.7.1 → 1.8.0 | updated | Within AndroidX stable line | Recorded (AndroidX release index); URL not re-fetched in this revision |
| AndroidX activity | direct | 1.11.0 → 1.13.0 | updated | Stable 1.13.0 released 2026-03-11; no AGP/Gradle/compileSdk requirement stated on the 1.13.0 section | [activity release notes](https://developer.android.com/jetpack/androidx/releases/activity) (verified) |
| AndroidX fragment | direct | 1.8.9 → 1.9.1 | updated | Recorded | Recorded; URL not re-fetched |
| AndroidX lifecycle | direct | 2.9.3 → 2.11.0 | updated | The app does not use Compose, so no Compose-driven requirement applies. 2.10.0 is needed only for bundled lint checks with AGP 9.5.0-alpha04+ | Recorded; the 2.10.0 lint note was seen on the activity page (verified) |
| AndroidX core-ktx | direct | 1.17.0 → 1.18.0 | updated | 1.18.0 changed compileSdk to API 36.1. **The earlier claim "1.19 needs AGP 9.1+ and compileSdk 37" is removed**: the 1.19.0 section states no AGP, Gradle or compileSdk requirement. The latest stable is 1.19.1 (2026-09-23); the Kotlin extensions were merged into `core` since 1.19.0-alpha01 | [core release notes](https://developer.android.com/jetpack/androidx/releases/core) (verified) |
| AndroidX annotation | direct | 1.9.1 → 1.11.0 | updated | Recorded | Recorded; URL not re-fetched |
| AndroidX webkit | direct | 1.14.0 → 1.18.0 | updated | Recorded | Recorded; URL not re-fetched |
| AndroidX startup | direct | 1.1.1 → 1.2.0 | updated | Recorded | Recorded; URL not re-fetched |
| AndroidX WorkManager | direct | 2.10.5 → 2.12.0 | updated | minSdk raised from API 23 to API 24, equal to the app's minSdk | [WorkManager release notes](https://developer.android.com/jetpack/androidx/releases/work) (verified: 2.12.0 released 2026-09-23, minSdk 24) |
| AndroidX test core | direct (test) | 1.5.0 → 1.7.0 | updated | Test-only | Recorded; URL not re-fetched |
| AndroidX leanback | direct (tv) | 1.0.0 → 1.2.0 | updated | Leanback is officially deprecated for TV; a Compose-based TV UI is the supported direction. Not a blocker for this release | Recorded; deprecation status not re-fetched in this revision |
| Material Components | direct | 1.13.0 → 1.14.0 | updated | Recorded from the Material GitHub tag page | Recorded; the release date year is not shown on the tag page |
| Retrofit | direct | 2.9.0 → 2.11.0 | updated within 2.x | 3.0.0 exists; major migration not adopted | Recorded; URL not re-fetched |
| OkHttp | direct (and transitive) | 4.10.0 → 4.12.0 | updated within 4.x | CVE-2021-0341 / GHSA-3cqm-mf7h-prrj affects below 4.9.2; 4.12.0 is above it. OkHttp 5 not adopted | [GHSA-3cqm-mf7h-prrj](https://github.com/advisories/GHSA-3cqm-mf7h-prrj) (verified: CVE-2021-0341, fixed in 4.9.2); [OkHttp parent-4.12.0 tag](https://github.com/square/okhttp/releases/tag/parent-4.12.0) (verified: tag exists, date 17 Oct with no year shown, no release notes beyond "Version 4.12.0") |
| Okio (transitive via OkHttp) | transitive | not declared → resolved version **unknown** | open | CVE-2023-3635 / GHSA-w33c-445m-f8w7 affects Okio below 3.4.0 and below 1.17.6. The resolved version has **not** been checked (no `okio` dependencyInsight run) | [GHSA-w33c-445m-f8w7](https://github.com/advisories/GHSA-w33c-445m-f8w7) (verified: CVE-2023-3635, patched in 3.4.0 and 1.17.6) |
| Glide | direct (tv) | 4.11.0 → 4.16.0 | updated within 4.x | Glide 5 not adopted | Recorded; URL not re-fetched |
| kotlinx-coroutines | direct (test) | 1.8.1 → 1.10.2 | updated | Catalog value feeds test code only; runtime resolves 1.9.0 through AndroidX | Recorded (resolved graph) |
| CommonMark | direct | 0.22.0 → 0.32.0 | updated | minSdk 24 | Recorded; URL not re-fetched |
| android-gif-drawable | direct | 1.2.29 → 1.2.32 | updated | Native JNI memory-safety fixes; no CVE attached | Recorded; URL not re-fetched |
| Mockito | direct (test) | 4.8.1 → 5.24.0 | updated | Test-only | Recorded |
| Robolectric | direct (test) | 4.10.2 → 4.10.2 | deferred | 4.x latest was **not** verified in this revision; an earlier note about 4.17 behavior is removed as unverified. The engine suite passes `testOpenVPN3Restriction` under upstream's 4.16.1 but not under 4.10.2 (see engine section) | Engine tests (recorded) |
| Koin | direct | 3.5.6 → 3.5.6 | deferred | Koin 4 exists; migration not required | Recorded |
| BouncyCastle (`bcprov`/`bcpkix` jdk15on) | direct (declared, unused) | 1.69 → 1.70 | updated | No module consumes these artifacts; no runtime exposure. CVE status **not verified** here. Kept because the catalog is shared with the engine | Recorded (no consumer found in app modules) |
| desugar_jdk_libs | direct (runtime, new) | new → 2.1.5 | updated (new) | Enables `java.time` on API 24–25 (see below) | Recorded: latest per Google Maven metadata |
| org.json | direct (engine test) | 20260814 → 20260814 | deferred | Unchanged; required by upstream engine tests through `libs.json` | Recorded: latest on Maven Central metadata |
| OSV (release-wide) | — | — | recorded | Recorded run returned no advisory for the 134 resolved coordinates. This is a point-in-time lookup, not a licence review and not re-run for this revision | Recorded |

### Notes on the table

- **AGP 9 trial (recorded, not repeated):** AGP 9.5.0, Gradle 9.6.1, Kotlin
  2.1.21, `android.builtInKotlin=false`, `android.newDsl=false`. The app modules
  configured after `resValues = true` was set per module (for `app_name`). The
  engine build script failed on `defaultConfig.targetSdk` (not present on library
  modules in that AGP) and on `kotlinOptions`. Note that Kotlin 2.1.21 is below
  the Kotlin plugin minimum recorded for AGP 9.1.1 (2.2.10); the trial therefore
  **needs a re-run** on a supported Kotlin version before any conclusion about AGP 9
  is drawn. AGP 10 removes the `newDsl` opt-out (recorded).
- **Desugaring:** `core`, `mobile` and `tv` use `java.time` (`AppFileLogStore`,
  `LogExportRetention`) with `minSdk = 24` and no desugaring. Without it, API 24–25
  devices would hit `NoClassDefFoundError`. Desugaring is enabled in all three
  modules (`isCoreLibraryDesugaringEnabled = true`), which clears the `NewApi`
  findings for `java.time`.

## A-AC2: OpenVPN Engine 0.7.69 integration

### Gitlink move

| | Commit / SHA |
|---|---|
| Engine repository | `zabotinegor/OpenVPNGateClientEngine`, branch `OpenVPNClientApp-integration` |
| Upstream | `schwabe/ics-openvpn` (0.7.69 sync merged into the integration branch) |
| Old gitlink | `1c6151961bc161a1cd681e03c496c0bd23fa7db9` |
| New gitlink | `4e228715b162959209426674815a33c7b22c16f6` |
| Change commit | `21474a5` (changes only `src/external/OpenVPNEngine`) |
| Sync recorded | 2026-11-09 (see the "Last verified against" line in `docs/guides/engine-update.md`) |

### Force-stop pieces (preserved)

The app-scoped force-stop patch is a local change that must survive every
upstream sync. Its pieces were checked against the new gitlink:

- `IOpenVPNServiceInternal.aidl`: `forceStopVPN()`, appended last.
- `OpenVPNThread`: process-wide `LIVE` registry (`liveProcesses()`),
  `requestTermination()`, `reserveForceKill()`, `killProcess()`.
- `OpenVPNService.forceStopVPN()`: binds to the exact process objects, returns
  codes `0`/`1`/`2`, schedules SIGKILL after `FORCE_KILL_GRACE_MS` (2000 ms).
- `DISCONNECT_VPN` intent fallback in `OpenVPNService`, called when `stopVPN`
  returns false.
- `DisconnectVPN` activity, which also calls `forceStopVPN()`.

Verified by `git diff` between the old and new gitlink for the four key files:
no change to the force-stop code. The code is present at the old pin, so a
rollback to the old pin restores it as well.

### Engine changes outside the force-stop patch (flagged)

The gitlink move also brings non-force-stop changes. These are **not** covered
by the recorded device run (which used the pre-move pin) and need verification:

- `OpenVpnService.kt` changed in this release; it is not "unchanged by design".
- `LocaleHelper` was removed in the engine; the app's locale behavior (Polish
  locale check in the smoke matrix) must be re-verified.
- `ProfileManager` recovery behavior changed; verify profile import and recovery
  on device.

### Test evidence

Engine suite (`./gradlew :openVpnEngine:testFullDebugUnitTest`), recorded on this branch:

- **2 of 48 tests fail**: `TestConfigParser.cleanReImport` and
  `TestRestrictions.testOpenVPN3Restriction`. The same two fail on the pre-upgrade
  catalog, so they are not introduced by the catalog change.
- Re-run on the exact release code (2026-11-16, clean checkout, same
  `:openVpnEngine:testFullDebugUnitTest` task): 48 tests, **45 passed, 2 failed
  (the two above), 1 skipped**. No new failures versus the documented baseline.
  The skipped test was not identified in this record.
- `cleanReImport` fails with "Bad backslash ('\') usage" while re-parsing a
  generated config. This points at a Windows path inside generated text; it was
  not confirmed on Linux.
- `testOpenVPN3Restriction` fails on its last assertion (a user preference is not
  preserved after a bundle without the key). Upstream's Robolectric 4.16.1 passes
  it; the client's 4.10.2 does not. Root cause in the test-to-Robolectric
  interaction is **not** established.

The engine suite therefore does **not** fully pass.

### Rollback procedure (engine)

Rollback restores the previous gitlink. It also drops the 0.7.69 sync fixes, so
it is a deliberate step, not a free revert.

1. In the submodule checkout, check out the old pin:
   `git -C src/external/OpenVPNEngine checkout 1c6151961bc161a1cd681e03c496c0bd23fa7db9`
2. In the superproject, stage the gitlink (`git add src/external/OpenVPNEngine`)
   and commit it as a normal change. Do not amend or rewrite history.
3. `git submodule update --init --recursive` on each working copy.
4. Rebuild from `src/`: `./gradlew --no-daemon assembleDebugApp`.
5. Verify force-stop: `git grep forceStopVPN` in the engine checkout must still
   list the pieces above, then run the orphaned-tunnel repro in
   [troubleshooting.md](../guides/troubleshooting.md#orphaned-vpn-tunnel-notification-vanished-and-stop-does-nothing-while-the-vpn-icon-remains--bug-12487v2bg8a).
6. Record the rollback reason and the lost 0.7.69 fixes in the release notes.

## Build and test evidence

Initialize both submodules before running resource or native builds. Provide
valid build-time `PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` using existing
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

Recorded results on this branch (Windows, JDK 17), not re-run for this revision:

- `testDebugUnitTestApp`, `assembleDebugApp`, `assembleReleaseApp` and
  `bundleReleaseApp`: pass.
- `:openVpnEngine:testFullDebugUnitTest`: 2 of 48 fail (see engine section).
- `lintDebug`: fails on historical findings unrelated to this work; no baseline
  was created. `core`: 2 `NewApi` for `android:defaultFocusHighlightEnabled`
  (API 26 theme attribute) and 6 `UseAppTint` (`android:tint` on `ImageView` in
  `content_settings.xml`); `mobile`: the same `NewApi`. These need a UI change and
  verification and are deferred to a separate story.
- The 46 `java.time` `NewApi` errors were real and are fixed by desugaring.
- The `tv` lint crash (`findFirCompiledSymbol` in lint's Kotlin script analysis)
  was caused by `apply(from = "../copy_drawables.gradle.kts")`, which lint
  resolved as a literal script path. `apply(from = rootProject.file(...))` in
  `mobile` and `tv` points at the same file, and the crash is gone.
- A machine-local `local.properties` trips `PropertyEscape`; it is not tracked.
- Resolved graphs (`:core`, `:mobile`, `:tv`, debug/release runtime classpaths,
  including the engine project): 134 external coordinates. Conflicts resolve
  upward and none is forced: OkHttp 3.14.9 → 4.12.0 (transitive),
  kotlin-stdlib → 2.1.20 (Kotlin BOM alignment from AndroidX),
  kotlinx-coroutines-core 1.7.3 → 1.9.0, gson 2.8.9 → 2.10.1, viewpager2 1.0.0 → 1.1.0.
- Script validators (`python3 -m unittest discover -s scripts/ci/tests -p 'test_*.py'`)
  check the CI and inventory scripts only; they are not Android runtime coverage.

On Windows, Git checks out the asio submodule symlink `asio/asio/include` as a
text file, so the native build fails with `asio.hpp` not found. Replace it with a
junction to `asio/include` locally; do not commit that change.

## CI and release pipelines (GitHub Actions)

CI and release run **only on GitHub Actions** in this repository. The workflows are
`ci-pr.yml` (pull request entry, calls `build-by-pull-request.yml` once),
`release-by-dev.yml`, `release-by-main.yml`, `release-by-tag.yml`, and the
trusted `ci-integration.yml` (`workflow_dispatch`). There is no Azure DevOps
pipeline file in this repository.

### Historical note: Azure DevOps fallback

An Azure DevOps fallback pipeline was planned earlier. The owner cancelled it. It is
not an active requirement, and its parity is not tracked here. Earlier parity
checks against it are removed from this document.

### Pipeline comparison

| Aspect | PR build (`build-by-pull-request.yml`) | Release workflows |
|---|---|---|
| Recursive submodules | yes | yes |
| JDK 17 (Temurin) | yes, with Gradle cache | yes |
| SWIG | `apt-get install swig` (unpinned) | same |
| SDK build-tools 36.0.0, CMake 3.22.1, NDK 29.0.14206865 | installed explicitly with `sdkmanager` | **not installed explicitly**; relies on runner image and AGP auto-download |
| Gradle setup | `setup-gradle@v3`, read-only cache | `setup-gradle@v3`, cache writes enabled |
| Signing | none | keystore decoded from secrets, tag builds only |
| Version metadata | commit SHA | tag/build-number derived |
| Artifacts | debug APKs, unsigned release sanity build | staged APK and AAB per launcher |

`ci-integration.yml` uses `setup-gradle@v4` with `validate-wrappers: true` before
the engine submodule is initialized, so the application wrapper is validated
while the engine's wrapper JAR is absent. It also installs explicit SDK packages,
including CMake 3.22.1 and NDK 29.0.14206865.

`setup-gradle@v4` was tried in the PR build and reverted: v4 validates every
`gradle-wrapper.jar` in the checkout, and the engine submodule's wrapper JAR is
not on the known list, so the PR build failed before any test ran. Extending
wrapper validation to the PR build needs the engine wrapper JAR problem solved,
either by deferred submodule checkout or by an engine wrapper update. This is
deferred with AGP 9.

The release workflows cannot be exercised without publishing. The missing explicit
NDK/CMake step is recorded rather than changed here. The inventory script reports
these package pins per workflow (`ci-sdk-package`) so the gap stays visible.

## A-AC6: upgrade and rollback per area

Rollback is by **file**, not by whole commit. `62924f0` bundles catalog, Gradle,
workflow, script and documentation changes; reverting the whole commit would also
revert unrelated work. For each file below, restore it from the parent of the
relevant commit, for example
`git checkout 62924f0^ -- src/gradle/libs.versions.toml`, then commit the restore
as a normal change.

| Area | Upgrade (what changed) | Rollback |
|---|---|---|
| Dependency catalog | `src/gradle/libs.versions.toml` (`62924f0`) | Restore the file from `62924f0^`. Re-run the resolution commands in the inventory section |
| Desugaring | `isCoreLibraryDesugaringEnabled` and `coreLibraryDesugaring(...)` in `src/core/build.gradle.kts`, `src/mobile/build.gradle.kts`, `src/tv/build.gradle.kts` (`62924f0`) | Restore the three build files from `62924f0^`. Note: without desugaring, `java.time` fails on API 24–25 |
| Launcher build scripts | `apply(from = rootProject.file("copy_drawables.gradle.kts"))` in `mobile` and `tv` (`62924f0`) | Restore from `62924f0^`. The previous literal path re-triggers the `tv` lint crash |
| PR workflows | `ci-pr.yml`, `build-by-pull-request.yml`, reusable build changes (`62924f0`; CI Gate commits `d431823`, `d85cb36`, `b3c999d`, `5b0eb25`) | Restore workflow files from the parent of the earliest CI commit in the chain. The branch-protection required check name (`CI Gate`) is a repository setting, not a git file: restore it manually if rolled back |
| Integration workflow | `ci-integration.yml` (`62924f0`) | Restore from `62924f0^`. It is `workflow_dispatch`-only and takes effect only once on the default branch |
| Engine | gitlink `1c615196` → `4e228715` (`21474a5`) | See the engine rollback procedure above |
| Documentation | `docs/operations/dependency-modernization.md`, `docs/INDEX.md`, `docs/guides/engine-update.md`, `docs/reference/build-config.md`, `docs/DEVELOPMENT.md` | Documentation only; no runtime effect |

## Regression checklist

Diff-proven status for this release:

**Not in the release diff set reviewed (carried over, still verify on device):**
- Android manifests and exported component settings.
- `minifyEnabled` and `shrinkResources` in mobile and tv release variants (kept on by design).
- `abiFilters`, `jniLibs.useLegacyPackaging` (preserved per module).
- App filter and settings files.

**Changed or unverified (must be exercised on device):**
- VPN service and controller foreground service path (`OpenVpnService.kt` changed).
- Updates UI and version metadata.
- OkHttp, Retrofit and Glide runtime behavior (version bumps, transitive OkHttp 3 → 4).
- Engine `LocaleHelper` removal and `ProfileManager` recovery change.
- R8 mapping and keep rules: **not re-inspected** for this revision; a release
  build succeeded in the recorded run, but its mapping output was not compared.
- Resolved Okio version: unknown (see A-AC1).

Pass criteria for any upgraded build (from the engine update guide and the
smoke matrix below): no `FATAL EXCEPTION`, no ANR, `CONNECTED` reached with
healthy watchdog and traffic delta, clean `DISCONNECTING → DISCONNECTED`, correct
`MainActivity` after notification tap.

## Device smoke matrix

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

Follow the device instructions in
`docs/operations/device-qa-phone.md`,
`docs/operations/device-qa-tv.md`,
`docs/guides/engine-update.md`, and
`tests/manual-e2e/environment/android-adb-vpn-qa-runbook.md`.
Include ADB/logcat evidence and test against at least one physical device or
suitable emulator for each required platform. An ordinary phone emulator does not
substitute for Leanback TV. Do not infer a PASS from a successful Gradle build.

## Smoke run record

### Build under test

Debug APKs of commit `211cad4` (version 1.0.0). This commit is on
`feature/android-dependency-modernization`, **not** on the release branch. It uses
the **pre-move** engine pin `1c615196` and predates the catalog bump being
released here. Therefore **no device evidence exists for the 0.7.69 gitlink
(`4e228715`) or for the release-branch build.** Evidence (logcat, screenshots, UI
dumps) is kept outside the repository.

### Mobile — Samsung SM-A715F, Android 13, 2026-11-01

| # | Scenario | Result | Observation |
| --- | --- | --- | --- |
| 1 | Fresh install and cold launch | PASS | Clear data, splash then main in about 8 s, no crash or ANR |
| 2 | Splash to main timing | PASS | Warm relaunch about 7 s, dominated by server preload |
| 3 | Server list loading | PASS | Countries and servers listed, live pings shown |
| 4 | Primary API fallback | PARTIAL | Offline cold start was graceful and recovered after reconnect; the primary-to-fallback URL switch itself was not exercised |
| 5 | Selection and favorites | PASS | Pinned section, persisted across force-stop, removal restored empty state |
| 6 | VPN permission and connect | PASS | Connected after auto-switching past dead servers |
| 7 | Traffic and watchdog | PASS | Public IP via tunnel, routes through the tunnel interface, repeated healthy watchdog for about 3 min |
| 8 | Pause and resume | PASS | Pause blocked traffic, resume re-created the tunnel |
| 9 | Stop and teardown | PASS | Notification and in-app stop left no tunnel, notification or service |
| 10 | Reconnect and auto-switch | PASS | Network drop recovered in about 41 s; one engine start per start action |
| 11 | Background and foreground | PASS | State accurate after relaunch in connected and connecting states |
| 12 | Notification tap and stop | PASS | One notification, tap opens main, stop tears down |
| 13 | DNS and per-app filter | PASS | Tunnel DNS and uid rules matched the settings; both restored |
| 14 | Update and version metadata | PASS | About shows 1.0.0 (63); update dialog and release notes open |
| 15 | Settings, locale, theme, log export | PASS | Polish locale and dark theme persisted; log export (java.time path) produced an archive |
| 16 | Full-session stability | PASS | No fatal exception, ANR or foreground-service exception; third-party library warnings only |

Observations, none blocking: `NoClassDefFoundError` warnings from the feature-flag
loader appear in several processes and were not compared with a pre-upgrade
baseline; Belarus servers show a 0 ms ping; pause and resume restarts the
duration counter.

Scope limit: this run is for `211cad4` on the pre-move engine pin. It does not
cover the engine changes listed in A-AC2.

### Android TV — Xiaomi Mi Box 4

Cases 1–3, 5–8 and 11 were executed with captured evidence; case 4 is BLOCKED
(same fallback limitation as mobile). The TV then became unreachable over network
ADB (the test method dropped Wi-Fi, which severed the ADB session), and cases 9,
10 and 12–16 are NOT RUN. The TV result is not final until those cases are
executed; no TV case is claimed beyond its captured evidence.

## Pipeline, release and rollback gates (CI Gate)

### Pull-request CI evidence

Android pull requests against `dev` or `main` run `.github/workflows/ci-pr.yml`,
which invokes the existing `build-by-pull-request.yml` exactly once and publishes
the **CI Gate** commit status on the actual PR head. The lightweight `changes` job
classifies the merge-base-to-PR-head diff using **only the version of
`scripts/ci/classify_pr_changes.py` from the trusted base revision**.

Only explicit documentation paths (for example `docs/**/*.md` and root `README.md`)
may skip the Gradle job. Source, resources, tests, Gradle configuration, workflow
code, scripts, assets, deleted or renamed code, submodule pointers and all unknown
paths require full CI. Unknown or missing classification cannot yield green
status. When the classifier does not yet exist on `dev`, the safe fallback is a
full build.

The reusable build receives only the `GH_PAT` secret (not every repository
secret), and the media checkout uses `persist-credentials: false`. The gate job
publishes **CI Gate** only while the live PR is open and its head and target base
branch tip still match the run. If the base advanced, the gate fails closed, and a
new `pull_request` event (push or "Update branch") is needed, because a plain
re-run reuses the original event payload.

Known limitations:

- The PAT is still available to the build job for the private media submodule
  fetch. A read-only media token, or a job that fetches media without running PR
  code, would close this.
- `pull_request` workflows come from the PR itself, so a same-repository author
  can still alter the gate publisher until it runs from a protected-base revision.
- Commit statuses attach to a head SHA, not to a base. A CI Gate success stays on
  that head if the target branch later advances. Until a push-to-base trigger
  exists this relies on the "Require branches to be up to date before merging"
  branch protection.
- Fork PRs receive no secrets and a read-only token.

For executable or CI-relevant diffs, `CI Gate` succeeds only when the same run's
reusable Android build **passes unit tests and mobile/TV debug and release
builds**. For a validated documentation-only diff it succeeds only if the Gradle
job was explicitly skipped. Any failure, cancelled run, missing output or
inconsistent state is not accepted as successful. There is no second Gradle run
for the status check, and no `paths-ignore` trigger that would leave a required
check pending. The reusable build also runs the Python CI contract tests under
`scripts/ci/tests` in that same job.

The `dev` branch ruleset should require **one GitHub Actions status, `CI Gate`**,
rather than the conditional job name `Build Debug APKs`. Do not remove the
required-status-check rule itself, bypass protection, or retain a duplicate
required build context (docs-only PRs would wait forever on the intentionally
skipped build).

### Integration fallback (`ci-integration.yml`)

`ci-integration.yml` is the trusted `workflow_dispatch` fallback. It validates the
PR, head and base values, runs against the exact merge of the PR head and the live
`dev` tip, and verifies GitHub's merge commit parents and tree before publishing
**Integration CI Gate**. The private media submodule is fetched only during
checkout of the trusted base, with non-persisted credentials. The merged PR code
never receives a PAT or signing key. Both gates fail closed if evidence is
missing, stale or contradictory.

**Bootstrap limitation:** GitHub dispatches a `workflow_dispatch` workflow only
when the workflow file also exists on the repository's default branch. The trusted
`dev` execution ref must additionally contain the workflow and verifier script.
Adding these files in a PR does not install them on either protected branch. Until
they are installed through an authorized release process, integration fallback is
unavailable, and its missing evidence must not be read as a passing result. The
normal `CI Gate` can already be exercised by the PR workflow after the feature
branch changes trigger a new run.

### Release gates

Preserve `src/core` runtime API semantics, VPN foreground-service permissions,
native library packaging, release minification and resource shrinking, and the
app-specific engine force-stop patch. Before publication, require the complete
device matrix, Gradle validation, deploy pipeline checks, a downgrade/recovery
plan, and explicit owner release authorization. No build signing or upload is
authorized by this document.

### Exact-code validation record (release 09.11.2026)

Validated on a clean checkout of the release code (2026-11-16):

- `assembleDebugApp`, `testDebugUnitTestApp`, `assembleReleaseApp` and `bundleReleaseApp` completed with exit code 0. Release outputs are unsigned locally; signing and version codes are produced by the release pipeline.
- `connectedDebugAndroidTestApp` on the phone: 21 of 21 instrumented tests passed.
- Lint (`core`, `mobile`, `tv`) reports only the historical findings already listed in this document.
- Phone regression (Samsung SM-A715F): 31 scenarios passed, plus the engine-process-crash recovery and shared-IP server scenarios passed in a follow-up run. A true SIGKILL of the engine process cannot be issued from the phone shell; a shell-induced crash of that process was used instead.
- Android TV regression (MIBOX4, Android 9): 24 scenarios passed, plus English/Polish localization and a same-signature update over the installed app (data preserved) and rejection of a differently signed package. Tapping INSTALL on a real offered release was not exercised because it would replace the build under test.
- TV observation: a display configuration change landing about 0.7-1.5 s after the system installer launches can end in "Package parse error" (FileProvider `SecurityException`). It reproduced only in that window and is attributed to the Android 9 system installer losing the URI grant on recreation, not to the app; the cached APK stays intact and a retry works. Hardening via an explicit `grantUriPermission` or the PackageInstaller session API is optional follow-up.
