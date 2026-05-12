# US-05: Gradle Build Performance Optimization — Manual QA Specification

**Story ID:** US-05  
**Manual QA Flow ID:** dev::us05-gradle-optimization  
**Branch:** dev  
**Test Date:** 2026-05-12  
**Environment:** Windows (Gradle wrapper via PowerShell)

## Objective
Validate that Gradle build performance optimizations have been successfully applied and deliver measurable improvements in build speed without regressions in safety, module integrity, or release hardening.

## Scope
- Gradle properties optimization (JVM heap, parallel, workers, caching, configure-on-demand)
- SWIG task cache behavior in OpenVPN engine module
- Build runtime performance and cache effectiveness
- Unit test and release build regression coverage
- Safety constraints: no ABI, module structure, or hardening regressions

## Acceptance Criteria Under Test

### AC-1: Gradle Parallel Execution & JVM Memory
- **AC-1.1**: `org.gradle.parallel=true` is set in gradle.properties
- **AC-1.2**: `org.gradle.jvmargs` is set to 4GB or greater
- **AC-1.3**: `org.gradle.workers.max` is configured and documented
- **AC-1.4**: Configuration values have no syntax errors and no secrets exposed

### AC-2: Build Caching
- **AC-2.1**: `org.gradle.caching=true` is set in gradle.properties
- **AC-2.2**: Cache is validated to improve repeated build performance
- **AC-2.3**: Cache directories and build integrity remain stable

### AC-3: Configure-on-Demand
- **AC-3.1**: `org.gradle.configureondemand=true` is set in gradle.properties
- **AC-3.2**: Configuration-on-demand does not prevent necessary module initialization

### AC-4: SWIG Task Caching
- **AC-4.1**: `generateOpenVPN3Swig*` tasks declare `inputs.files()` with source files
- **AC-4.2**: `generateOpenVPN3Swig*` tasks declare `outputs.dir()` with output directories
- **AC-4.3**: Outputs are cache-eligible and benefit from incremental cache hits

### AC-5: Build Profiling & Reporting
- **AC-5.1**: Profile build completes successfully and generates report
- **AC-5.2**: Profile report contains actionable metrics (build time, task counts, cache hits)
- **AC-5.3**: Second build shows cache-hit improvement over profile baseline

### AC-6: Release Build Validation
- **AC-6.1**: Release build (`./gradlew assembleReleaseApp -P...`) succeeds with all required properties
- **AC-6.2**: Release build output APKs are generated and assembly succeeds
- **AC-6.3**: Unit tests pass (`./gradlew testDebugUnitTestApp` executes without regression; 389/389 minimum)

## Test Surfaces
1. **src/gradle.properties** — Configuration settings validation
2. **src/external/OpenVPNEngine/main/build.gradle.kts** — SWIG task cache config
3. **src/build/reports/profile/** — Profile report output
4. **src/build/reports/problems/problems-report.html** — Problem detection
5. **Build outputs** — APK assemblies and test results

## Test Cases Reference
- **MQ-01**: Clean profile build and timing baseline
- **MQ-02**: Cached rebuild comparison and speedup measurement
- **MQ-03**: Release build and install smoke test
- **MQ-04**: Unit test regression check
- **MQ-05**: Configuration and safety constraints validation

## Pass/Fail Criteria
- **PASSED**: All AC-1 through AC-6.3 checks validated with measurable evidence; no regressions; profile shows cache improvement; release/unit tests succeed
- **BLOCKED**: Environment/tooling blocker prevents required validation (e.g., missing device for install/launch smoke, Gradle failure preventing profile generation)
- **FAILED**: Reproducible defect violating any AC or causing measurable regression

## Evidence Requirements
- Build command outputs with timestamps and durations
- Profile report file path and key metrics
- Timing comparison table (clean vs. cached)
- APK file checksums or paths (for install validation)
- AC verdict matrix with pass/fail per criterion
- Any defects captured in us-05-defects.json with full repro steps
