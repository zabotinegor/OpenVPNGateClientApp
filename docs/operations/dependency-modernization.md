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
- [Dependency resolution and vulnerability reports](#dependency-resolution-and-vulnerability-reports)
- [R8 and minified release device validation](#r8-and-minified-release-device-validation)
- [Pipeline, release and rollback gates (CI Gate)](#pipeline-release-and-rollback-gates-ci-gate)

## Status and scope

This document records the dependency and toolchain review for the
`feature/release/09.11.2026` release. It separates three kinds of statement:

- **Verified** — checked on 2026-11-16 against an official page or metadata URL
  that is named in the table, or against a repository file in the release diff.
- **Recorded** — taken from an earlier run on this branch (test output, OSV
  lookup, device session). It was not re-executed for this revision.
- **Not established / open** — no official source could be reached, or the check
  was not run. Do not rely on these until checked.

Nothing here claims that the catalog is the complete installed dependency graph.
Device evidence is summarized in the [Smoke run record](#smoke-run-record) and the
exact-code validation record at the end of the CI section; the two are read together.

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

The `okio` insight was run on 2026-11-16 for this revision: Okio resolves to
**3.6.0** (okio-jvm 3.6.0) in `:core`, `:mobile` and `:tv`, pulled in by OkHttp 4.12.0
(see the CVE-2023-3635 row in the A-AC1 table).

## Toolchain compatibility boundary

Verified on 2026-11-16 against the AGP release notes (URLs in the A-AC1 table):

- AGP 8.13 supports a maximum of **API 36.1**, requires **Gradle 8.13** and **JDK 17**.
  This is the currently deployed toolchain (`AGP 8.13.2`, Gradle wrapper 8.13).
- AGP 9.1 requires **Gradle 9.3.1**, **JDK 17** and Kotlin Gradle plugin **2.2.10**.
- AGP 9.5 supports a maximum of **API 37**, requires **Gradle 9.6.0** and **JDK 17**.
- The external engine module targets `compileSdk`/`targetSdk` 37 (engine repository,
  `OpenVPNClientApp-integration`). The app on AGP 8.13 therefore builds an engine
  that targets a newer API than AGP 8.13 documents as supported. The build works in
  the recorded run; this combination should not be extended without re-verification.

Not established in this revision: the AGP 9.0 requirements, the maximum API level
of AGP 9.1 (the 9.1 page was confirmed only for Gradle, JDK and KGP), and default
built-in Kotlin and R8 behavior. Re-check these before any AGP 9 change.

AGP 9 is a coordinated migration (Gradle, Kotlin plugin defaults, variant DSL,
R8), not an independent catalog edit. The engine pin belongs to
`OpenVPNClientApp-integration`; use the `update-engine` workflow if an engine
change becomes necessary.

## A-AC1: dependency and toolchain review

Versions below are taken from the release diff (`62924f0` for the catalog and
Gradle files, `21474a5` for the engine gitlink), from `src/gradle/libs.versions.toml`,
and from the module build files. "Direct" means declared in
`src/gradle/libs.versions.toml` or a module build script; "transitive" means
resolved through another dependency and not declared by the app.

Disposition values: **updated** (changed in this release), **deferred** (not
changed, reason given), **blocked** (cannot change until a named prerequisite),
**open** (not established).

Source labels used in the Source column. All URLs below were fetched on
**2026-11-16** for this revision, and the stated fact was present in the fetched
content. Dates are the ones printed on the cited page.

- **Verified 2026-11-16** — the exact URL named was fetched and confirmed the fact.
- **Repository file** — read from this checkout (not an external source).
- **Recorded** — taken from an earlier run on this branch; not re-executed.
- **No official source reachable (reason)** — the fetch failed or the page does not
  carry the fact. The row's claim is reduced to what the other sources show.

Maven metadata files (`repo1.maven.org`, `dl.google.com`) list published versions
only; they carry no release dates. Every date below comes from a release page, not
from Maven metadata. Several GitHub repositories have moved (`square/okhttp` and
`square/retrofit` redirect to `lysine-dev/…`); the redirected URL is cited.

Source abbreviations: `AX/<name>` is
`https://developer.android.com/jetpack/androidx/releases/<name>`; `M2/<path>` is
`https://repo1.maven.org/maven2/<path>/maven-metadata.xml`.

| Component | Class | Before → after | Disposition | Reason / risk | Source (what was verified) |
|---|---|---|---|---|---|
| Android Gradle Plugin | direct (build) | 8.13.2 → 8.13.2 | blocked | AGP 9 requires the engine and Gradle migration; the trial failed in the engine build script (see notes below) | Verified 2026-11-16: [AGP 9.5.0 notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes) (titled October 2026; max API 37, Gradle 9.6.0, JDK 17); [AGP 8.13 notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes) (max API 36.1, Gradle 8.13, JDK 17); [AGP 9.1 notes](https://developer.android.com/build/releases/agp-9-1-0-release-notes) (Gradle 9.3.1, KGP 2.2.10, JDK 17). The catalog pin `agp = "8.13.2"` is a repository file. The older `/build/releases/gradle-plugin` URL now redirects to the 9.5 notes |
| Gradle wrapper | build tool | 8.13 → 8.13 | blocked | Follows AGP 8.13; a wrapper bump is coupled to the AGP decision. AGP 9.5 needs Gradle 9.6.0 | Verified 2026-11-16: [Gradle 8.13 release notes](https://docs.gradle.org/8.13/release-notes.html) (page exists); [Gradle versions service](https://services.gradle.org/versions/all): 8.13 build time 2025-02-25, 8.14.7 (2026-11-11), 9.6.0 (2026-06-18), 9.9.1 (2026-11-10, flagged current); [current release notes](https://docs.gradle.org/current/release-notes.html) titled Gradle 9.9.1. Pin: `src/gradle/wrapper/gradle-wrapper.properties` (`gradle-8.13-bin.zip`, repository file) |
| JDK / CI toolchain | build tool | JDK 17 (Temurin) → JDK 17 | deferred | Matches AGP 8.13, 9.1 and 9.5 requirements (JDK 17). App modules `kotlinOptions { jvmTarget = "11" }` (`core`, `mobile`, `tv`); engine `jvmTarget = "17"` | Verified 2026-11-16: AGP pages above (JDK 17); [setup-java v6.3.0](https://github.com/actions/setup-java/releases/tag/v6.3.0) (2026-11-14, latest). Repository files: Temurin 17 in the release workflows, `build-by-pull-request.yml` and `ci-integration.yml`; module build files for `jvmTarget` |
| Kotlin | direct (build) | 2.0.21 → 2.0.21 | deferred | `kotlinOptions {}` is deprecated since Kotlin 2.0.0 and Kotlin 2.2.0 raises its deprecation level to error; the engine and the three app modules still use `kotlinOptions`, so a Kotlin 2.2+ move needs a `compilerOptions {}` migration first. Latest stable listed is 2.4.21 | Verified 2026-11-16: [Kotlin releases](https://kotlinlang.org/docs/releases.html) (2.0.21 released October 10, 2024; 2.4.21 listed); [Kotlin 2.4.21 on GitHub](https://github.com/JetBrains/kotlin/releases/tag/v2.4.21) (2026-10-08); [Gradle compiler options](https://kotlinlang.org/docs/gradle-compiler-options.html) (deprecated from 2.0.0); [What's new in Kotlin 2.2](https://kotlinlang.org/docs/whatsnew22.html) (deprecation level raised to error). [M2/org/jetbrains/kotlin/kotlin-stdlib](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/maven-metadata.xml) lists 2.5.0-RC as newest (pre-release, not "latest stable") |
| AndroidX appcompat | direct | 1.7.1 → 1.8.0 | updated | Catalog `appcompat = "1.8.0"`; stable on the release page | Verified 2026-11-16: [AX/appcompat](https://developer.android.com/jetpack/androidx/releases/appcompat): stable 1.8.0, section dated August 12, 2026; 1.7.1 dated June 4, 2025 |
| AndroidX activity | direct | 1.11.0 → 1.13.0 | updated | 1.13.0 is dated March 11, 2026; its section states no AGP, Gradle or compileSdk requirement. The compileSdk 37.1 note on the page belongs to 1.14.0 alpha Compose libraries (not adopted). Page also notes lifecycle 2.10.0 is needed for bundled lifecycle lint checks with AGP 9.5.0-alpha04 or later (not applicable on AGP 8.13) | Verified 2026-11-16: [AX/activity](https://developer.android.com/jetpack/androidx/releases/activity) |
| AndroidX fragment | direct | 1.8.9 → 1.9.1 | updated | 1.9.1 dated September 23, 2026; the library is in maintenance mode (critical fixes only) per the page | Verified 2026-11-16: [AX/fragment](https://developer.android.com/jetpack/androidx/releases/fragment) |
| AndroidX lifecycle | direct | 2.9.3 → 2.11.0 | updated | No Compose is used by the app, so Compose requirements do not apply (the page: Compose compileSdk 37 needs AGP 9.2.0). 2.11.0 dated June 17, 2026. Default minSdk moved from API 21 to 23 in 2.10; app minSdk is 24 | Verified 2026-11-16: [AX/lifecycle](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| AndroidX core-ktx | direct | 1.17.0 → 1.18.0 | updated | 1.18.0 (March 11, 2026) changed compileSdk from API 36 to 36.1. The 1.19.0 section (June 3, 2026) states no AGP, Gradle or compileSdk requirement; the earlier claim that "1.19 needs AGP 9.1+ and compileSdk 37" is removed. Latest stable 1.19.1 (September 23, 2026) is not adopted | Verified 2026-11-16: [AX/core](https://developer.android.com/jetpack/androidx/releases/core) |
| AndroidX annotation | direct | 1.9.1 → 1.11.0 | updated | Catalog `1.11.0`; stable, dated September 23, 2026 | Verified 2026-11-16: [AX/annotation](https://developer.android.com/jetpack/androidx/releases/annotation) |
| AndroidX webkit | direct | 1.14.0 → 1.18.0 | updated | 1.18.0 dated October 21, 2026; the page records the library minSdk raised to API 24, equal to the app minSdk | Verified 2026-11-16: [AX/webkit](https://developer.android.com/jetpack/androidx/releases/webkit) |
| AndroidX startup | direct | 1.1.1 → 1.2.0 | updated | 1.2.0 dated September 18, 2024 | Verified 2026-11-16: [AX/startup](https://developer.android.com/jetpack/androidx/releases/startup) |
| AndroidX WorkManager | direct | 2.10.5 → 2.12.0 | updated | 2.12.0 dated September 23, 2026; minSdk raised from API 23 to API 24, equal to the app minSdk (24) | Verified 2026-11-16: [AX/work](https://developer.android.com/jetpack/androidx/releases/work) |
| AndroidX test core / ext junit / espresso | direct (test) | core 1.5.0 → 1.7.0; ext junit → 1.3.0; espresso 3.7.0 | updated | Test-only. The release table lists stable core 1.7.0, espresso 3.7.0 and ext.junit 1.3.0 | Verified 2026-11-16: [AX/test](https://developer.android.com/jetpack/androidx/releases/test) |
| AndroidX leanback | direct (tv) | 1.0.0 → 1.2.0 | updated | The page states the artifact and its classes are deprecated ("Use Compose for TV instead"); 1.2.0 stable (April 23, 2025). Not a blocker for this release | Verified 2026-11-16: [AX/leanback](https://developer.android.com/jetpack/androidx/releases/leanback) |
| AndroidX security-crypto | direct (engine) | 1.0.0 → 1.1.0 | updated | 1.1.0 dated July 30, 2025; 1.1.0-beta01 notes "Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore". Not adopted as a change of direction in this release | Verified 2026-11-16: [AX/security](https://developer.android.com/jetpack/androidx/releases/security) |
| AndroidX viewpager2 | direct (engine) | 1.0.0 → 1.1.0 | updated | 1.1.0 dated May 14, 2024 (also confirmed in the resolved graph, see Build section) | Verified 2026-11-16: [AX/viewpager2](https://developer.android.com/jetpack/androidx/releases/viewpager2) |
| AndroidX recyclerview | direct | → 1.4.0 | updated | 1.4.0 dated January 15, 2025; page marks the library as maintenance mode | Verified 2026-11-16: [AX/recyclerview](https://developer.android.com/jetpack/androidx/releases/recyclerview) |
| AndroidX cardview | direct | 1.0.0 → 1.0.0 | deferred | 1.0.0 dated September 21, 2018 (latest stable on the page); maintenance mode | Verified 2026-11-16: [AX/cardview](https://developer.android.com/jetpack/androidx/releases/cardview) |
| AndroidX preference | direct | 1.2.1 → 1.2.1 | deferred | 1.2.1 dated July 26, 2023; maintenance mode | Verified 2026-11-16: [AX/preference](https://developer.android.com/jetpack/androidx/releases/preference) |
| AndroidX constraintlayout | direct / engine | 2.2.2 → 2.2.2 | deferred | 2.2.2 dated July 29, 2026; page marks the library as maintenance mode. Not replaced here | Verified 2026-11-16: [AX/constraintlayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout) |
| Material Components | direct | 1.13.0 → 1.14.0 | updated | 1.14.0 released 2026-05-13 (1.13.0: 2025-09-03). Google Maven lists 1.14.0 as latest and release. Earlier note that the `repo1` path returned 404 is explained: the artifact is published on Google Maven | Verified 2026-11-16: [release 1.14.0](https://github.com/material-components/material-components-android/releases/tag/1.14.0); [Google Maven metadata](https://dl.google.com/dl/android/maven2/com/google/android/material/material/maven-metadata.xml) |
| Retrofit | direct | 2.9.0 → 2.11.0 | updated within 2.x | 2.11.0 released 2024-03-28. Maven lists 2.9.0, 2.11.0 and 2.12.0 (highest 2.x, not adopted); 3.0.0 (2025-05-15) is the Maven and GitHub latest, a major migration not adopted | Verified 2026-11-16: [Retrofit 2.11.0](https://github.com/lysine-dev/retrofit/releases/tag/2.11.0); [Retrofit 3.0.0](https://github.com/lysine-dev/retrofit/releases/tag/3.0.0); [M2/com/squareup/retrofit2/retrofit](https://repo1.maven.org/maven2/com/squareup/retrofit2/retrofit/maven-metadata.xml) |
| OkHttp | direct (and transitive) | 4.10.0 → 4.12.0 | updated within 4.x | GHSA-3cqm-mf7h-prrj (CVE-2021-0341, "Square OkHttp can accept the wrong certificate") lists `< 4.9.2` as affected and 4.9.2 as patched; 4.12.0 (2023-10-17) is above it. 4.12.1 (2026-10-22) is the highest 4.x and is not adopted. OkHttp 5 (Maven latest 5.5.1) not adopted | Verified 2026-11-16: [GHSA-3cqm-mf7h-prrj](https://github.com/advisories/GHSA-3cqm-mf7h-prrj); [parent-4.12.0](https://github.com/lysine-dev/okhttp/releases/tag/parent-4.12.0); [parent-4.12.1](https://github.com/lysine-dev/okhttp/releases/tag/parent-4.12.1); [M2/com/squareup/okhttp3/okhttp](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml) |
| Okio (transitive via OkHttp) | transitive | not declared → resolved version 3.6.0 | closed for this revision | GHSA-w33c-445m-f8w7 (CVE-2023-3635): `okio-jvm` affected `>= 2.0.0-RC1, < 3.4.0`, patched 3.4.0 (`okio` also `< 1.17.6`, patched 1.17.6). The resolved 3.6.0 (via OkHttp 4.12.0, `dependencyInsight`, 2026-11-16) is above 3.4.0. Maven lists 3.6.0; latest okio-jvm is 3.18.2 (not the resolved version) | Verified 2026-11-16: [GHSA-w33c-445m-f8w7](https://github.com/advisories/GHSA-w33c-445m-f8w7); [M2/com/squareup/okio/okio-jvm](https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/maven-metadata.xml). Resolution: Recorded in this document (run 2026-11-16) |
| Glide | direct (tv) | 4.11.0 → 4.16.0 | updated within 4.x | 4.11.0 released 2020-01-08, 4.16.0 released 2023-08-21; 4.16.0 is the highest 4.x on Maven. Latest is Glide 5.0.9 (2026-07-11), not adopted | Verified 2026-11-16: [Glide 4.11.0](https://github.com/bumptech/glide/releases/tag/v4.11.0); [Glide 4.16.0](https://github.com/bumptech/glide/releases/tag/v4.16.0); [Glide 5.0.9](https://github.com/bumptech/glide/releases/tag/v5.0.9); [M2/com/github/bumptech/glide/glide](https://repo1.maven.org/maven2/com/github/bumptech/glide/glide/maven-metadata.xml) |
| kotlinx-coroutines | direct (test) | 1.8.1 → 1.10.2 | updated | 1.10.2 released 2025-04-08; latest 1.11.0 (2026-05-08) is not adopted. The catalog value feeds test code; runtime resolves 1.9.0 through AndroidX (Recorded resolution). Maven lists 1.8.1, 1.9.0, 1.10.2 and 1.11.0 | Verified 2026-11-16: [1.10.2](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.10.2); [1.11.0](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0); [M2/org/jetbrains/kotlinx/kotlinx-coroutines-core](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml) |
| CommonMark | direct | 0.22.0 → 0.32.0 | updated | 0.22.0 released 2024-03-15; 0.32.0 released 2026-11-01 (latest on GitHub and Maven). The 0.32.0 notes say: "Fix Android incompatibility with minSdk < 30: The minSdk required is now 24" (app minSdk is 24). Other API changes between 0.22 and 0.32 were not reviewed here | Verified 2026-11-16: [commonmark-java 0.32.0](https://github.com/commonmark/commonmark-java/releases/tag/commonmark-parent-0.32.0); [0.22.0](https://github.com/commonmark/commonmark-java/releases/tag/commonmark-parent-0.22.0); [M2/org/commonmark/commonmark](https://repo1.maven.org/maven2/org/commonmark/commonmark/maven-metadata.xml) |
| android-gif-drawable | direct | 1.2.29 → 1.2.32 | updated | 1.2.29 released 2024-07-31; 1.2.32 released 2026-06-18. The 1.2.32 notes list "Fix JNI memory-safety bugs" and a use-after-free fix after `ReleaseStringUTFChars` | Verified 2026-11-16: [v1.2.32](https://github.com/koral--/android-gif-drawable/releases/tag/v1.2.32); [v1.2.29](https://github.com/koral--/android-gif-drawable/releases/tag/v1.2.29); [M2/pl/droidsonroids/gif/android-gif-drawable](https://repo1.maven.org/maven2/pl/droidsonroids/gif/android-gif-drawable/maven-metadata.xml) |
| Mockito | direct (test) | 4.8.1 → 5.24.0 | updated | Test-only. 4.8.1 released 2022-10-17; 5.24.0 released 2026-09-23 (latest on GitHub and Maven) | Verified 2026-11-16: [v5.24.0](https://github.com/mockito/mockito/releases/tag/v5.24.0); [v4.8.1](https://github.com/mockito/mockito/releases/tag/v4.8.1); [M2/org/mockito/mockito-core](https://repo1.maven.org/maven2/org/mockito/mockito-core/maven-metadata.xml) |
| Robolectric | direct (test) | 4.10.2 → 4.10.2 | deferred | 4.10.2 released 2023-05-05; 4.16.1 released 2026-01-21; 4.18 (2026-11-13) is the latest. The engine suite passes `testOpenVPN3Restriction` under upstream's 4.16.1 but not under 4.10.2 (Recorded, see engine section) | Verified 2026-11-16: [robolectric-4.10.2](https://github.com/robolectric/robolectric/releases/tag/robolectric-4.10.2); [robolectric-4.16.1](https://github.com/robolectric/robolectric/releases/tag/robolectric-4.16.1); [robolectric-4.18](https://github.com/robolectric/robolectric/releases/tag/robolectric-4.18); [M2/org/robolectric/robolectric](https://repo1.maven.org/maven2/org/robolectric/robolectric/maven-metadata.xml) |
| Koin | direct | 3.5.6 → 3.5.6 | deferred | 3.5.6 released 2024-04-12. Latest stable release is Koin 4.2.2 (2026-06-15); Maven lists 4.3.0-Beta1 as newest (pre-release). Migration to 4 not required in this release | Verified 2026-11-16: [Koin 3.5.6](https://github.com/InsertKoinIO/koin/releases/tag/3.5.6); [Koin 4.2.2](https://github.com/InsertKoinIO/koin/releases/tag/4.2.2); [M2/io/insert-koin/koin-core](https://repo1.maven.org/maven2/io/insert-koin/koin-core/maven-metadata.xml) |
| BouncyCastle (`bcprov`/`bcpkix` jdk15on) | direct (declared, unused) | 1.69 → 1.70 | updated | No module consumes these artifacts in the app modules, and the resolved runtime graph does not contain BouncyCastle (see the resolution section). Maven lists 1.69 and 1.70 for both artifacts and 1.70 is the newest `jdk15on` version | Verified 2026-11-16: [M2/org/bouncycastle/bcprov-jdk15on](https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/maven-metadata.xml); [M2/org/bouncycastle/bcpkix-jdk15on](https://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk15on/maven-metadata.xml). CVE status: **No official source reachable** (`bouncycastle.org/releasenotes.html` redirects to the download list, which names releases 1.69 and 1.70 but carries no advisory data); not established |
| desugar_jdk_libs | direct (runtime, new) | new → 2.1.5 | updated (new) | Enables `java.time` on API 24–25 (see Notes below). Google Maven lists 2.1.5 as latest and release; the developer.android.com setup snippet shows 2.0.3 and does not mention 2.1.5. The GitHub releases page does not list 2.1.5 (not used as evidence) | Verified 2026-11-16: [Google Maven metadata](https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs/maven-metadata.xml); [desugaring page](https://developer.android.com/studio/write/java8-support) (library-desugaring snippet, `java.time` in the supported subset); [API table](https://developer.android.com/studio/write/java8-support-table) (`java.time` classes). No release date: **No official source reachable** (GitHub releases page has no 2.1.5 entry) |
| JUnit 4 | direct (test) | 4.13.2 → 4.13.2 | deferred | 4.13.2 released 2021-02-13 and is the latest 4.x on GitHub and Maven | Verified 2026-11-16: [JUnit 4.13.2](https://github.com/junit-team/junit4/releases/tag/r4.13.2); [M2/junit/junit](https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml) |
| Timber | direct | 5.0.1 → 5.0.1 | deferred | 5.0.1 released 2021-08-13 and is the latest on GitHub and Maven | Verified 2026-11-16: [Timber 5.0.1](https://github.com/JakeWharton/timber/releases/tag/5.0.1); [M2/com/jakewharton/timber/timber](https://repo1.maven.org/maven2/com/jakewharton/timber/timber/maven-metadata.xml) |
| MPAndroidChart | direct (engine) | v3.1.0 → v3.1.0 | deferred | v3.1.0 released 2019-03-20; v4.0.1 (2026-09-28) is the latest on GitHub. Major migration not in scope. Consumed through JitPack (not covered by the Maven checks) | Verified 2026-11-16: [v3.1.0](https://github.com/PhilJay/MPAndroidChart/releases/tag/v3.1.0); [v4.0.1](https://github.com/PhilJay/MPAndroidChart/releases/tag/v4.0.1) |
| org.json | direct (engine test) | 20260814 → 20260814 | deferred | Unchanged; required by upstream engine tests through `libs.json`. Maven lists 20260814 as latest | Verified 2026-11-16: [M2/org/json/json](https://repo1.maven.org/maven2/org/json/json/maven-metadata.xml) |
| GitHub Action `actions/checkout` | CI | pinned v4 → v4 | deferred | Latest release v7.0.1 (2026-07-20); v4.0.0 released 2023-10-17. Pins (repository files): `ci-pr.yml`, `build-by-pull-request.yml`, `ci-integration.yml` and the three release workflows | Verified 2026-11-16: [v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1); [v4.0.0](https://github.com/actions/checkout/releases/tag/v4.0.0); [latest redirect](https://github.com/actions/checkout/releases/latest) |
| GitHub Action `actions/setup-java` | CI | pinned v4 → v4 | deferred | Latest v6.3.0 (2026-11-14); v4.0.0 released 2023-11-29. JDK 17 Temurin. Pins: `build-by-pull-request.yml`, `ci-integration.yml`, the three release workflows | Verified 2026-11-16: [v6.3.0](https://github.com/actions/setup-java/releases/tag/v6.3.0); [v4.0.0](https://github.com/actions/setup-java/releases/tag/v4.0.0) |
| GitHub Action `gradle/actions/setup-gradle` | CI | pinned v3 (PR and release workflows), v4 (`ci-integration.yml`) → same | deferred | Latest v6.4.1 (2026-10-28); v3.0.0 released 2024-01-31, v4.0.0 released 2024-08-07. v4 was tried in the PR build and reverted (see CI section) | Verified 2026-11-16: [v6.4.1](https://github.com/gradle/actions/releases/tag/v6.4.1); [v3.0.0](https://github.com/gradle/actions/releases/tag/v3.0.0); [v4.0.0](https://github.com/gradle/actions/releases/tag/v4.0.0) |
| GitHub Action `android-actions/setup-android` | CI | pinned v3 → v3 | deferred | Latest v4.0.4 (2026-09-17); v3.0.0 released 2023-09-25. Pins: `build-by-pull-request.yml`, `ci-integration.yml` | Verified 2026-11-16: [v4.0.4](https://github.com/android-actions/setup-android/releases/tag/v4.0.4); [v3.0.0](https://github.com/android-actions/setup-android/releases/tag/v3.0.0) |
| GitHub Action `actions/github-script` | CI | pinned v7 → v7 | deferred | Latest v9.0.0 (2026-04-09); v7.0.0 released 2023-11-13. Pins: `ci-pr.yml`, `build-by-pull-request.yml` and the three release workflows | Verified 2026-11-16: [v9.0.0](https://github.com/actions/github-script/releases/tag/v9.0.0); [v7.0.0](https://github.com/actions/github-script/releases/tag/v7.0.0) |
| GitHub Action `actions/upload-artifact` | CI | pinned v4 → v4 | deferred | Latest v7.0.2 (2026-10-07); v4.0.0 released 2023-12-14. Pin: `build-by-pull-request.yml` | Verified 2026-11-16: [v7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2); [v4.0.0](https://github.com/actions/upload-artifact/releases/tag/v4.0.0) |
| GitHub Action `ncipollo/release-action` | CI (release) | pinned v1 → v1 | deferred | Latest v1.21.0 (2026-03-14). The workflows pin the floating major tag `v1`; the tag listing was not used to claim the tag is frozen. Pin: the three release workflows | Verified 2026-11-16: [v1.21.0](https://github.com/ncipollo/release-action/releases/tag/v1.21.0) |
| OSV (release-wide) | — | — | recorded | Recorded run returned no advisory for the resolved coordinates (counts in the resolution section). Point-in-time lookup; not a licence review and not re-run for the dependency catalog edits in this document. See [Dependency resolution and vulnerability reports](#dependency-resolution-and-vulnerability-reports) | Recorded |

Rows without a "No official source reachable" statement are fully **Verified
2026-11-16** for the claim stated. The only unreachable items are the BouncyCastle
CVE status and the desugar_jdk_libs release date; both are stated as not
established. Every other claim in the table was confirmed from a page fetched in this review.

### Notes on the table

- **AGP 9 trial (recorded, not repeated):** AGP 9.5.0, Gradle 9.6.1, Kotlin
  2.1.21, `android.builtInKotlin=false`, `android.newDsl=false`. The app modules
  configured after `resValues = true` was set per module (for `app_name`). The
  engine build script failed on `defaultConfig.targetSdk` (not present on library
  modules in that AGP) and on `kotlinOptions`. Note that Kotlin 2.1.21 is below
  the Kotlin plugin minimum for AGP 9.1 (2.2.10, verified in the table above); the trial therefore
  **needs a re-run** on a supported Kotlin version before any conclusion about AGP 9
  is drawn. AGP 10 removes the `newDsl` opt-out (recorded).
- **Desugaring:** `core`, `mobile` and `tv` use `java.time` (`AppFileLogStore`,
  `LogExportRetention`) with `minSdk = 24` and no desugaring. Without it, API 24–25
  devices would hit `NoClassDefFoundError`. Desugaring is enabled in all three
  modules (`isCoreLibraryDesugaringEnabled = true`), which clears the `NewApi`
  findings for `java.time`.
- **Versions above the catalog:** several rows record a newer version that exists on
  the source (OkHttp 4.12.1, Retrofit 2.12.0, Glide 5.x, Kotlinx-coroutines 1.11.0,
  Koin 4.x, Robolectric 4.18). They are listed so the decision not to adopt them is
  visible. None of them is adopted in this release.

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

The gitlink move also brings non-force-stop changes. The
[exact-code validation record](#exact-code-validation-record-release-09112026)
reports phone and TV runs on the release code, which includes the 0.7.69 pin.
That record does **not** itemize the scenarios against the changes below, so each
item needs an explicit mapping before it is treated as verified:

- `OpenVpnService.kt` changed in this release; it is not "unchanged by design".
- `LocaleHelper` was removed in the engine; the app's locale behavior (Polish
  locale check in the smoke matrix) is covered by the TV localization result in
  the exact-code record. The phone locale mapping is not itemized.
- `ProfileManager` recovery behavior changed; the record does not itemize profile
  import and recovery. Mapping: open.

The earlier phone run (`211cad4`, section [Smoke run record](#smoke-run-record))
used the pre-move pin and does **not** cover these changes.

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

Recorded results on this branch (Windows, JDK 17), not re-run for this revision
unless stated in the exact-code validation record:

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
  Okio resolves to 3.6.0 (see the dependency resolution section below).
- Script validators (`python3 -m unittest discover -s scripts/ci/tests -p 'test_*.py'`)
  check the CI and inventory scripts only; they are not Android runtime coverage.

On Windows, Git checks out the asio submodule symlink `asio/asio/include` as a
text file, so the native build fails with `asio.hpp` not found. Replace it with a
junction to `asio/include` locally; do not commit that change.

## CI and release pipelines (GitHub Actions)

CI and release run **only on GitHub Actions** in this repository. The workflows are:

| Workflow | Trigger | What it does |
|---|---|---|
| `ci-pr.yml` | `pull_request` to `dev` and `main` (types `opened`, `synchronize`, `reopened`, `ready_for_review`); `workflow_dispatch` | Classifies the PR diff with the trusted base's `scripts/ci/classify_pr_changes.py`, calls `build-by-pull-request.yml` once when heavy CI is needed, and publishes the **CI Gate** status on the exact PR head. Documentation-only diffs skip the Gradle job |
| `build-by-pull-request.yml` | `workflow_call` from `ci-pr.yml`; `workflow_dispatch` | Runs Python CI contract tests, installs SWIG, JDK 17, SDK packages (platform 36, build-tools 36.0.0, CMake 3.22.1, NDK 29.0.14206865), unit tests for the app, mobile and TV debug APKs, and an unsigned release sanity build. Uploads debug APKs and comments the links on the PR. Signing: **none** |
| `release-by-dev.yml` ("Beta Release From Dev") | `push` to `dev` limited to paths `src/**`, `media`, `media/**`; `workflow_dispatch` | Beta: signed APK and AAB per launcher, published as a GitHub **prerelease** and to the versions API. See [Release workflows in detail](#release-workflows-in-detail) |
| `release-by-main.yml` ("Release from Main") | `push` to `main` (no path filter); `workflow_dispatch` | Stable: signed APK and AAB per launcher, published as a normal GitHub release and to the versions API |
| `release-by-tag.yml` ("Release from Tag") | `push` of tags matching `v*`, skipped when the tag name contains `-auto` | Stable: same build, signing and publication path as `release-by-main.yml`, with the version taken from the tag |
| `ci-integration.yml` | `workflow_dispatch` only | Trusted fallback; see the Integration fallback section |

Signing is therefore **not** limited to tag builds. All three release workflows decode
the keystore from secrets and sign; the PR build does not sign.

### Historical note: Azure DevOps fallback

An Azure DevOps fallback pipeline was planned earlier. The owner cancelled it. It is
not an active requirement, and its parity is not tracked here. Earlier parity
checks against it are removed from this document. There is no Azure DevOps
pipeline file in this repository.

### Pipeline comparison

| Aspect | PR build (`build-by-pull-request.yml`) | Release workflows (`release-by-*.yml`) |
|---|---|---|
| Recursive submodules | yes | yes |
| JDK 17 (Temurin) | yes, with Gradle cache | yes |
| SWIG | `apt-get install swig` (unpinned) | same |
| SDK build-tools 36.0.0, CMake 3.22.1, NDK 29.0.14206865 | installed explicitly with `sdkmanager` | **not installed explicitly**; relies on runner image and AGP auto-download |
| Gradle setup | `setup-gradle@v3` with `gradle-version: wrapper` and `cache-read-only: true`; `setup-java` also uses `cache: 'gradle'` | `setup-gradle@v3` with `gradle-version: wrapper` and `cache-read-only: false` set explicitly in all three files; `setup-java` has no `cache:` input |
| Checkout credentials | `persist-credentials: false` on the checkout; checkout token is `GH_PAT` | checkout uses the `GH_PAT` token with the default credential persistence (`persist-credentials` is not set in the release workflows) |
| Signing | **none** (no keystore step; unsigned release sanity build only) | `Decode Keystore` step in each workflow: `SIGNING_KEY_BASE64` is decoded to `src/keystore.jks` and `src/keystore.properties` is written from `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`; the `release` signing config in `mobile` and `tv` reads that file |
| Version metadata | no release version is derived; the PR head's short commit SHA names the debug artifacts | derived per workflow (see the next section); `versionName` and `versionCode` are passed to Gradle as `-PappVersionName`, `-PappVersionCodeMobile`, `-PappVersionCodeTv` |
| Artifacts | debug APKs (`upload-artifact`), unsigned release sanity build | staged APK and AAB per launcher, renamed to `OpenVPNGateClient_<launcher>_<version>_build<N>.{apk,aab}` and attached to a GitHub release |

### Release workflows in detail

Read from `.github/workflows/release-by-dev.yml`, `release-by-main.yml` and
`release-by-tag.yml`. The three share one `concurrency` group (`release-build-number`,
no cancel-in-progress), so release builds run one at a time.

| | `release-by-dev.yml` (beta) | `release-by-main.yml` (stable) | `release-by-tag.yml` (stable) |
|---|---|---|---|
| Trigger | `push` to `dev` with paths `src/**`, `media`, `media/**`; `workflow_dispatch` | `push` to `main`; `workflow_dispatch` | `push` of tag `v*`; the job is skipped when `github.ref_name` contains `-auto` |
| Gate job | `detect-changes`: manual run always proceeds; a push proceeds only when the pushed range changed `src/` or the `media` submodule path | same logic | none |
| `appReleaseType` | `beta` (`ORG_GRADLE_PROJECT_appReleaseType`) | `release` | `release` |
| `versionName` | Latest non-beta `v1.1.*` tag, patch + 1 (starts at `1.1.0` if none); app version `<version>-beta.<n>` where `n` is the highest existing `v<version>-beta.*` tag + 1 (or 1) | Same base version rule, no beta suffix | The pushed tag with the `-auto(N)` or `(N)` suffix stripped and the leading `v` removed |
| `versionCode` | `BUILD_NUMBER` = highest existing `*-auto(N)` tag number + 1, used for both mobile and TV | same | same |
| Git tag created | `v<version>-beta.<n>-auto(<BUILD_NUMBER>)` at the workflow SHA (existing tag tolerated) | `v<version>-auto(<BUILD_NUMBER>)` | build marker `tagbuild-v<version>-auto(<BUILD_NUMBER>)`; the release itself is on the pushed tag |
| GitHub release | `ncipollo/release-action@v1`, `prerelease: true`, name "Beta Release <tag>" | `prerelease: false`, name "Release <tag>" | `prerelease: false`, name "Release <tag>" |
| Release assets | `OpenVPNGateClient_{mobile,tv}_<app_version>_build<N>.{apk,aab}` (4 files) | `OpenVPNGateClient_{mobile,tv}_<version>_build<N>.{apk,aab}` (4 files) | same as main |
| Versions API | `POST {VERSIONS_API_BASE_URL}/api/v1/versions` with `X-API-Key: VERSIONS_API_KEY`; `releaseType` 1; asset URLs point at the GitHub release; size and SHA-256 per asset; HTTP 409 is treated as "already exists" (warning) | same call with `releaseType` 0 | same call with `releaseType` 0 |
| Release-notes translation | after a newly created version, an `auto-translate` call per language in `RELEASE_NOTES_AUTO_TRANSLATE_LANGUAGES_JSON` (`en`, `ru`, `pl`; source locale `en`) | same | same |
| Signing | `Decode Keystore` (`SIGNING_KEY_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`), then `./gradlew --no-daemon --stacktrace stageReleaseArtifacts` in `src/` | `Decode Keystore`, then `./gradlew stageReleaseArtifacts` in `src/` | same as main |
| Gradle cache | `setup-gradle@v3`, `cache-read-only: false` | same | same |
| Secrets and variables used | `GH_PAT`, `SIGNING_KEY_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`, `VERSIONS_API_KEY`; vars `PRIMARY_SERVERS_URL`, `FALLBACK_SERVERS_URL`, `VERSIONS_API_BASE_URL` | same | same |
| Azure | none | none | none |

Contract tests for the signing and cache statements above:
`python3 -m unittest discover -s scripts/ci/tests -p 'test_release_workflow_contracts.py'`
(`scripts/ci/tests/test_release_workflow_contracts.py`). They assert that each release
workflow has a `Decode Keystore` step using `SIGNING_KEY_BASE64` that runs before
`stageReleaseArtifacts` in `src/`, that `mobile` and `tv` sign `release` from
`keystore.properties`, that `ci-pr.yml` and `build-by-pull-request.yml` never decode a
keystore or reference signing secrets, and that `cache-read-only` is set explicitly
(`false` in the release workflows, `true` in the PR build). The tests read the files
as text; they do not execute a release.

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
- `minifyEnabled` and `shrinkResources` in mobile and tv release variants (kept on by design). See [R8 and minified release device validation](#r8-and-minified-release-device-validation).
- `abiFilters`, `jniLibs.useLegacyPackaging` (preserved per module).
- App filter and settings files.

**Changed (exercised on device; see the exact-code record and the R8 section for what was run):**
- VPN service and controller foreground service path (`OpenVpnService.kt` changed).
- Updates UI and version metadata.
- OkHttp, Retrofit and Glide runtime behavior (version bumps, transitive OkHttp 3 → 4).
- Engine `LocaleHelper` removal and `ProfileManager` recovery change.
- R8 and minified release behavior: the minified, resource-shrunk release builds
  were exercised on device (phone 28 of 30 checks passed, TV 20 passed with 2 blocked,
  none failed; see [R8 and minified release device validation](#r8-and-minified-release-device-validation)).
  Not done: `mapping.txt` deobfuscation and a comparison of the mapping output or keep
  rules against the pre-upgrade build. The ART "Unable to resolve ... CountryV2/ServerV2
  annotation class" warning on the minified build is informational only.
- Resolved Okio version: 3.6.0 (see A-AC1); dependency resolution and the OSV lookup
  are in [Dependency resolution and vulnerability reports](#dependency-resolution-and-vulnerability-reports).

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

Two device records exist for this release. They are kept separate because they
test different code.

1. **Earlier run (`211cad4`, pre-move engine pin).** Debug APKs of commit `211cad4`
   (version 1.0.0), on `feature/android-dependency-modernization`, **not** on the
   release branch. It uses the pre-move engine pin `1c615196` and predates the
   catalog bump being released here. The phone and TV sections below record this run.
   It does not cover the 0.7.69 gitlink (`4e228715`).
2. **Exact-code validation (2026-11-16, release code).** Summarized in the
   [exact-code validation record](#exact-code-validation-record-release-09112026).
   It covers the release code, including the 0.7.69 pin, and is the device evidence
   for this release.

Evidence (logcat, screenshots, UI dumps) is kept outside the repository.

### Mobile — Samsung SM-A715F, Android 13, 2026-11-01 (run on `211cad4`)

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
cover the engine changes listed in A-AC2. Those are covered only at the level of
the exact-code validation record.

### Android TV — Xiaomi Mi Box 4 (run on `211cad4`)

Cases 1–3, 5–8 and 11 were executed with captured evidence; case 4 is BLOCKED
(same fallback limitation as mobile). The TV then became unreachable over network
ADB (the test method dropped Wi-Fi, which severed the ADB session), and cases 9,
10 and 12–16 are NOT RUN in this run. The notification tap and stop, DNS, and
auto-switch cases are therefore not covered by this run.

Follow-up on the exact-code build (2026-11-16, MIBOX4, see
`.sdlc/evidence/qa/r8/tv/release-09-11-2026-qa-r8-tv.md`): DNS provider selection
and its effect on the tunnel passed. Notification Pause and Stop passed at intent
level only (the same intents the actions fire were sent to the engine service from
the app uid, on the debug build; shell uid was refused on the release build), not as
a physical tap. The notification tap is **BLOCKED** (the TV has no notification
shade and shell cannot fire the PendingIntent). Auto-switch is **BLOCKED** (it needs
a stalled connect, and the device is not rooted, so traffic to a server could not be
blocked without altering live server data).

## Dependency resolution and vulnerability reports

Run on 2026-11-16 against the release branch (code identical to the validated
build; only documentation differs):

- Gradle `dependencies` for the debug and release runtime classpaths of `:core`,
  `:mobile`, `:tv`, and `:openVpnEngine` `fullReleaseRuntimeClasspath`: all resolved
  cleanly, with no unresolved or failed coordinates.
- Okio 3.6.0 (okio-jvm 3.6.0) is pulled by OkHttp 4.12.0 (declared directly, via
  okhttp-sse, and via Retrofit 2.11.0, whose requested OkHttp 3.14.9 is upgraded).
- Other resolved versions: retrofit 2.11.0, glide 4.16.0, koin 3.5.6, work-runtime
  2.12.0, material 1.14.0, leanback 1.2.0, coroutines 1.9.0, kotlin-stdlib 2.1.20,
  gson 2.10.1 (2.8.9 in the standalone engine report). No BouncyCastle and no Moshi
  on these classpaths.
- The repository has no Gradle vulnerability plugin.
- OSV `querybatch` on 136 artifact/version pairs from the release runtime classpaths
  returned 0 vulnerability records. A control query (gson 2.8.8, okio 1.17.5)
  returned GHSA records, so the query path works. The 136 pairs here and the 134
  coordinates counted in the Build section come from different runs and were not
  reconciled.
- Not covered: compile, test and annotation-processor classpaths, build plugins,
  native engine libraries, and the JitPack MPAndroidChart v3.1.0 artifact.
- BouncyCastle advisories are not applicable to the resolved runtime graph (the
  library is absent). The BouncyCastle CVE status itself could not be established from
  an official page (see the A-AC1 row).

## R8 and minified release device validation

Run on 2026-11-16 with the minified, resource-shrunk release builds (re-signed
copies of the unsigned release APKs with the debug key, installed over the debug
builds). Source code is identical to HEAD: the build predates the documentation
commits, none of which touch `src/`. Records:
`.sdlc/evidence/qa/r8/phone/release-09-11-2026-qa-r8-phone.md` and
`.sdlc/evidence/qa/r8/tv/release-09-11-2026-qa-r8-tv.md`.

- **Phone (Samsung SM-A715F, Android 13):** 28 of 30 checks passed, 1 BLOCKED
  (landscape rotation could not be forced; a font-scale recreation with an active
  tunnel was used instead and passed), 1 N/A (Glide is TV-only). The flow covered
  cold start, server list, favorites, connect with traffic, notification pause and
  stop, resume, reconnect, update dialog, DNS, split tunneling, language, About,
  background survival and process restart. Manifest permissions match debug and the
  arm64-v8a and armeabi-v7a engine libraries load.
- **TV (Xiaomi MIBOX4, Android 9):** 20 passed, 2 BLOCKED (notification tap,
  auto-switch; see the TV follow-up above), 0 failed. Notification pause and stop
  were verified at intent level.
- **Crash-trace check:** logcat scans of the app processes showed no FATAL
  EXCEPTION, ANR, NoSuchMethod/Field, AbstractMethodError, Koin, Gson or Retrofit
  converter errors. ClassNotFound hits came from system or WebView processes.
- **Warning:** ART logs "Unable to resolve ... CountryV2/ServerV2 annotation class"
  on the minified build only; parsing works, so it is informational. Not
  deobfuscated with `mapping.txt`; flag-image loading through Glide was not
  separately confirmed on the TV.
- **Residual:** a real tap on the notification and the auto-switch path are not
  covered on TV; engine behaviour there is unchanged upstream.

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
- Coverage relative to the earlier TV run: the first MIBOX4 run did not execute the notification tap and stop, DNS and auto-switch cases (see [Smoke run record](#android-tv--xiaomi-mi-box-4-run-on-211cad4)). The follow-up on the exact-code build covered DNS (passed), notification pause/stop (intent level) and recorded the notification tap and auto-switch as BLOCKED; see the follow-up paragraph in that section.
- Dependency reports and R8 device validation for this revision are **completed** and recorded in [Dependency resolution and vulnerability reports](#dependency-resolution-and-vulnerability-reports) and [R8 and minified release device validation](#r8-and-minified-release-device-validation): all runtime classpaths resolved cleanly, OSV returned 0 records for the resolved runtime pairs, the phone R8 run passed 28 of 30 (1 BLOCKED, 1 N/A) and the TV run passed 20 of 22 (2 BLOCKED, 0 failed). Remaining limitations: `mapping.txt` deobfuscation was not done, and the notification tap and auto-switch on TV are BLOCKED.
