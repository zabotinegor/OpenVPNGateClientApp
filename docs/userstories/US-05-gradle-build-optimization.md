# US-05 - Gradle Build Performance Optimization for Debug and Release APK Builds

## User story

**As** a developer working on the OpenVPN Gate Android client,  
**I want** the Gradle build task for debug and release APK assembly to complete faster (target 50% reduction from current 15–30 minutes),  
**so that** development iteration cycles are shorter, CI feedback is quicker, and developer productivity is improved.

---

## Background

### Current build times

Repository observation and developer feedback confirm that `./gradlew assembleDebugApp` and `./gradlew assembleReleaseApp` regularly take **15–30 minutes** on typical developer machines. This impacts:

- development iteration cycles when rebuilding after code changes
- pull request CI feedback latency
- overall developer experience and productivity

### Current build configuration

The project:

- Uses multi-module Gradle with `:core`, `:mobile`, `:tv` apps, and `:openVpnEngine` (external native C/C++ build)
- Has `org.gradle.jvmargs=-Xmx2048m` (2 GB JVM heap, potentially undersized)
- Has `org.gradle.parallel=true` commented out in `gradle.properties` (parallel builds disabled)
- Uses `externalNativeBuild` with CMake and NDK to compile OpenVPN engine native code
- Runs SWIG codegen tasks per build variant (generateOpenVPN3SwigDebug, generateOpenVPN3SwigRelease)
- Enables R8 minification and resource shrinking for release builds only
- Does not define build caching behavior in `gradle.properties`

### Investigation findings

Build profiling and configuration analysis identifies these primary bottlenecks:

| Bottleneck | Impact | Mitigation category |
|------------|--------|-------------------|
| Parallel build disabled | Sequential module compilation | Enable org.gradle.parallel=true |
| Insufficient JVM heap (2 GB) | Memory pressure, GC overhead | Increase org.gradle.jvmargs to 4 GB |
| Build caching not enabled | Redundant task reruns across builds | Enable org.gradle.caching=true |
| Full module configuration on-demand | Slow project graph evaluation | Enable org.gradle.configureondemand=true |
| No SWIG cache hints | Regenerates bindings on every build | Add cache inputs/outputs to SWIG tasks |

### Non-goals in this optimization story

- Reducing APK size (subject of separate optimization effort)
- Changing build logic, architecture, or module structure
- Introducing new tooling or build automation
- Removing native code compilation or changing ABI support
- Optimizing release hardening (minification, shrinking remain as configured)

---

## Acceptance criteria

### AC-1 — Gradle parallel execution and JVM memory

| ID | Criterion |
|----|-----------|
| AC-1.1 | `src/gradle.properties` enables `org.gradle.parallel=true` |
| AC-1.2 | `src/gradle.properties` sets `org.gradle.workers.max` to a reasonable value (e.g., 8) documented in comments |
| AC-1.3 | `src/gradle.properties` increases `org.gradle.jvmargs` from 2 GB to 4 GB (e.g., `-Xmx4096m`) |
| AC-1.4 | Both changes are inline-documented in `gradle.properties` explaining the purpose (parallel builds, heap allocation) |

### AC-2 — Gradle build caching

| ID | Criterion |
|----|-----------|
| AC-2.1 | `src/gradle.properties` enables `org.gradle.caching=true` |
| AC-2.2 | Build cache is configured for local use (no remote cache required) |
| AC-2.3 | A comment in `gradle.properties` explains that build cache stores task outputs for faster rebuilds |

### AC-3 — On-demand configuration

| ID | Criterion |
|----|-----------|
| AC-3.1 | `src/gradle.properties` enables `org.gradle.configureondemand=true` |
| AC-3.2 | A comment explains that on-demand configuration evaluates only modules needed for the requested task |

### AC-4 — SWIG cache optimization (OpenVPN Engine)

| ID | Criterion |
|----|-----------|
| AC-4.1 | The `generateOpenVPN3Swig*` Exec tasks in `src/external/OpenVPNEngine/main/build.gradle.kts` have explicit `inputs.files()` declarations for SWIG interface files |
| AC-4.2 | The `generateOpenVPN3Swig*` tasks have explicit `outputs.dir()` declarations for the generated source directory |
| AC-4.3 | Task outputs are correctly registered so Gradle recognizes cache-eligible outputs |

### AC-5 — Validation and measurement

| ID | Criterion |
|----|-----------|
| AC-5.1 | After implementation, `./gradlew assembleDebugApp --profile` completes and produces a build profile report in `src/build/reports/profile/` |
| AC-5.2 | Profile report is included in validation evidence, showing task execution times before and after optimization changes |
| AC-5.3 | A second `./gradlew assembleDebugApp` run (leveraging cache) completes noticeably faster than the first run, confirming cache is active |
| AC-5.4 | Validation notes document observed build time reduction (target: 50% improvement, or ≥ 7.5–15 minute reduction from baseline) |

### AC-6 — Release build validation

| ID | Criterion |
|----|-----------|
| AC-6.1 | `./gradlew assembleReleaseApp -PappVersionName=... -PappVersionCode=... -PPRIMARY_SERVERS_URL=... -PFALLBACK_SERVERS_URL=... -PPRIMARY_SERVERS_V2_URL=...` completes successfully |
| AC-6.2 | Release build output APKs are valid and installable on test devices |
| AC-6.3 | Unit tests pass: `./gradlew testDebugUnitTestApp` executes without regression |

---

## Out of scope

- Changing module structure or module interdependencies
- Removing or altering native code compilation (NDK/CMake builds must remain intact)
- Modifying build logic, plugin configurations, or Gradle plugin versions
- Changing APK size, minification rules, or resource shrinking behavior
- Optimizing instrumented test builds (connectedDebugAndroidTest)
- Implementing remote build cache or shared build infrastructure
- Changing ABI support or build variant strategy
- Changing server URL endpoints or build-time injection logic

---

## Risks and open questions

| ID | Risk or question | Current handling |
|----|------------------|------------------|
| R-1 | Parallel builds may cause race conditions if modules have undeclared dependencies | Assumption: project follows Android Gradle plugin best practices and modules are properly decoupled. If issues arise, parallel mode can be tuned or reverted per task. |
| R-2 | On-demand configuration may cause slower first-run configuration in rare edge cases | Acceptable: overall build time impact is positive. First-run slowness is offset by faster parallel task execution. |
| R-3 | Build cache on developer machines may accumulate stale artifacts over time | No action required for initial rollout. Cache maintenance can be documented in team guidelines later (e.g., `./gradlew cleanBuildCache` as needed). |
| R-4 | Native code changes (OpenVPN engine, NDK) still require full recompilation regardless of cache | Expected and acceptable. Build cache optimization applies to Java/Kotlin and task-level caching; native builds are managed by CMake/NDK. |
| R-5 | SWIG cache hints may not apply if CMake build is the bottleneck | SWIG optimization is a best-effort improvement. If native code dominates build time, additional investigation is needed (e.g., CMake parallelization, NDK options). |

---

## Implementation notes

These notes are guidance for likely implementation surfaces, not a mandatory design.

### Likely affected areas

1. `src/gradle.properties` — primary configuration changes (add org.gradle.parallel, org.gradle.workers.max, increase JVM memory, add caching, add on-demand configuration)
2. `src/external/OpenVPNEngine/main/build.gradle.kts` — SWIG task input/output declarations in the `registerGenTask()` function

### Implementation strategy

**Phase 1: Low-risk JVM and parallel settings**
- Uncomment and enable `org.gradle.parallel=true`
- Add `org.gradle.workers.max=8` (or match machine core count)
- Increase `org.gradle.jvmargs` to `-Xmx4096m`
- Add inline comments explaining each setting

**Phase 2: Build cache**
- Add `org.gradle.caching=true`
- Add brief documentation comment

**Phase 3: On-demand configuration**
- Add `org.gradle.configureondemand=true`
- Add brief documentation comment

**Phase 4: SWIG cache hints**
- In `registerGenTask()`, ensure `inputs.files()` and `outputs.dir()` are declared for SWIG Exec tasks
- Ensure generated source directories are correctly registered

**Phase 5: Validation**
- Run profiled builds before and after to capture build times
- Run clean build, then incremental build to confirm cache is working
- Validate release build and unit tests still pass

### Expected build time impact

- First build (clean): likely modest improvement (10–20%) due to parallel execution and cache preparation
- Incremental builds (with cache): significant improvement (30–50%) due to cached task outputs
- Typical development iteration: moderate improvement (20–40%) assuming mixed clean and cached scenarios

---

## Test scenarios

### Automated validation

| ID | Scenario |
|----|-----------|
| TS-1 | Run `./gradlew assembleDebugApp --profile` on a clean build directory; profile report is generated successfully |
| TS-2 | Run `./gradlew assembleDebugApp` immediately after TS-1 (cache active); observe measurably faster execution |
| TS-3 | Run `./gradlew testDebugUnitTestApp` and confirm all tests pass without regression |
| TS-4 | Run `./gradlew assembleReleaseApp -P...` with required parameters; release APK builds successfully |
| TS-5 | Install release APK on a test device and confirm app launches without crash |
| TS-6 | Delete `.gradle/` directory and re-run `./gradlew assembleDebugApp` to confirm build works without cache |

### Build time measurement (manual)

| ID | Scenario |
|----|-----------|
| MQ-1 | Measure clean build time: `time ./gradlew clean assembleDebugApp` before and after implementation |
| MQ-2 | Measure cached incremental build time: `time ./gradlew assembleDebugApp` on second run, compare to baseline |
| MQ-3 | Record profile report from TS-1 and compare task execution times with historical baseline if available |

---

## Definition of done

- All Gradle property changes in `src/gradle.properties` are in place with inline documentation
- SWIG Exec tasks in `src/external/OpenVPNEngine/main/build.gradle.kts` have explicit input/output declarations
- Profile report demonstrates measurable build time improvement (target: 50% reduction, minimum 20% acceptable)
- Incremental builds with cache are visibly faster than clean builds
- Unit tests pass (`./gradlew testDebugUnitTestApp`)
- Release build succeeds with all required parameters
- Release APK is installable and launch-tested
- Validation evidence includes before/after profile reports and build time measurements
- All changes are committed and pass CI pipeline
