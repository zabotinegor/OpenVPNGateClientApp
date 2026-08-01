# Testing guidelines

Android testing only. Run all Gradle commands **from `src/`**.

## The three layers

| Layer | Runs on | Task |
|---|---|---|
| JVM unit tests | host JVM, no Android framework | `./gradlew testDebugUnitTestApp` |
| Robolectric | host JVM, shadowed Android framework | same task; selected by test source |
| Instrumented (Espresso) | a real device or emulator | `./gradlew connectedDebugAndroidTestApp` (phone), `connectedDebugAndroidTestTv` (Leanback target) |

Prefer the **aggregate** tasks defined in `src/build.gradle.kts` over per-module invocations.

## The engine is not covered by the app's test task

`testDebugUnitTestApp` does **not** run the engine's own unit tests — `:openVpnEngine` is not a
dependency of that aggregate. When merged upstream commits add or change engine-side tests, run:

```bash
./gradlew :openVpnEngine:testFullDebugUnitTest
```

This is the single easiest thing to miss after an engine bump. See
[engine-submodule.md](engine-submodule.md).

## Choosing a layer

- Business logic, repositories, mappers, state transitions → **JVM unit test**. Fastest, and where
  most of this codebase's logic lives (`src/core`).
- Anything needing `Context`, resources, or `SharedPreferences` semantics → **Robolectric**.
- Anything needing a real VPN interface, a real `VpnService` permission grant, notification
  behaviour, or D-pad/focus traversal → **instrumented**, and be aware the result is device-dependent.

Instrumented results vary by OEM. Known device-specific failures and their causes are recorded in
[../operations/device-qa-miui.md](../operations/device-qa-miui.md) and
[../operations/device-qa-tv.md](../operations/device-qa-tv.md) — check there before concluding a
test is broken.

## Before building anything

```bash
git submodule update --init --recursive
```

Required before any build that touches resources or native code.

## Manual QA

Device techniques (ADB one-liners, prefs inspection, log filters) are in
[../guides/adb-cookbook.md](../guides/adb-cookbook.md). Story-scoped QA specs, cases and suites live
in ClickUp, not in this repository.

## What a change should be validated with

Validate at the narrowest correct level, then widen only if a contract changed:

- Logic change → JVM unit test in the owning module.
- Persistence or settings shape change → Robolectric test plus a migration check
  (see [../reference/settings-keys.md](../reference/settings-keys.md)).
- UI flow or focus behaviour → instrumented test on the relevant form factor.
- Engine bump → the full checklist in [../guides/engine-update.md](../guides/engine-update.md).

*Last verified against: `src/build.gradle.kts` aggregate tasks (2026-07-31).*
