# Android memory and resource lifecycle profiling

Use this runbook for a **measured** audit of the mobile and TV clients. A coroutine
scope, retained cache or foreground service is not automatically a leak. Always
compare memory snapshots and retained-object references across repeatable lifecycle
cycles before classifying a defect.

## Prerequisites

- Build the same debug/release variant for both baseline and verification.
- Record device model, Android version, app version/commit, build variant, free
  storage and VPN connection state; use equivalent conditions after each change.
- Start from a clean app launch for each run, but **do not force-stop between
  individual cycles**: that would hide session-scoped accumulation.
- Profile the app's main process and the OpenVPN service/engine processes separately
  (see [vpn-connection](../features/vpn-connection.md) for the process boundary).
- Use a local/mock backend for repeated update download tests when feasible,
  avoiding unnecessary real APK downloads and network variability.

## Repeatable scenario matrix

Perform at least ten cycles of each applicable user flow, taking an initial,
middle and final memory snapshot, and repeat after any code change.

| Flow | Action | Expected release point |
| --- | --- | --- |
| Navigation | Main → About → Back; Settings → Back | Old Activities, views and dialogs no longer retained |
| Server lists | Country list → country servers → Back, repeat | ViewModels/adapters released; deliberate asynchronous server backfill stays bounded |
| Filter | Open app filter, scroll icons, close, repeat | Icon loading jobs stop and abandoned views are released |
| Updates | Open update prompt; start download with a controlled local endpoint; finish or navigate away | Progress UI callbacks stop after dismissal; incomplete downloads belong to the separate APK-cache story |
| VPN | Connect → pause/resume → disconnect → reconnect | Active VPN service/resources match the actual connection state; intentional foreground lifetime not reported as leak |
| Server push | Background/foreground app with SSE refresh cycles | One expected live connection when foregrounded, no accumulating event sources or reconnection jobs |
| Android TV | Repeat TV drawer/D-pad navigation, list and Update flows | No retained dismissed screens or unbounded callback queues |

## Capture and interpret evidence

1. Attach Android Studio **Memory Profiler** to the relevant app process. Note Java
   heap, native heap, graphics and allocation trends; take heap dumps at the
   same idle point before and after repeated cycles.
2. Use `adb shell dumpsys meminfo <applicationId>` at identical checkpoints.
   Save the full output locally alongside scenario, elapsed time, active service
   state, iteration count and build SHA. The launcher and OpenVPN engine may run
   in different processes; inspect both process names before drawing conclusions.
3. Inspect heap dumps for **retained destroyed Activity/Fragment/View/Dialog
   instances** and the concrete GC-root reference path. If using LeakCanary in
   a locally instrumented debug build, keep analysis dependencies out of
   production/release variants unless separately approved.
4. Repeat baseline and changed builds under similar conditions, with several
   readings after the system has settled. A single increased PSS value, a
   legitimate reusable cache, or a still-connected VPN service does not prove
   leakage.
5. For every confirmed defect record before/after allocations or retained-object
   paths, affected process, reproduction steps and the code-level release path.
   For an inconclusive signal, record it as **suspected, not verified**.

## Automated regression and cancellation checks

The shared update progress dialog uses `LatestUpdateProgressDispatcher`, which
keeps only the latest progress snapshot and **one queued UI callback**. Run:

```bash
cd src
./gradlew :core:testDebugUnitTest --tests "*LatestUpdateProgressDispatcherTest"
./gradlew testDebugUnitTestApp
```

Rerun representative mobile and TV instrumented/device checks using
`connectedDebugAndroidTestApp` and `connectedDebugAndroidTestTv` where the
appropriate targets exist. Manual on-device updates must also verify that the
last displayed progress is correct and no dialog survives navigation away.

This guide is a **measurement procedure**, not a record that measurements have
already occurred.
