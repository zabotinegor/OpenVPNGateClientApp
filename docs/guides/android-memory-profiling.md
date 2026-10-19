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

The sections above are a **measurement procedure**. The measurements actually taken
so far, and their limits, are recorded in "Measured audit results" below.

## Measured audit results (2026-10-19)

Method: debug build of this branch, phone SM-A715F and Android TV MIBOX4. Each checkpoint
forces a GC (`run-as <pkg> kill -10 <pid>`) and reads `dumpsys meminfo` (Activities, Views,
ViewRootImpl, Java heap, PSS). The reusable scripts are in
[memory-profiling-scripts](memory-profiling-scripts/); run them with `OUT=<dir>`.
Heap dumps (hprof) were **not** captured, so reference paths were not inspected; object
counts come from `dumpsys meminfo`.

| Scenario | Cycles | Result | Classification |
| --- | --- | --- | --- |
| Phone: drawer → Server/DNS/Filter/Settings/About → back | 15 × 5 screens | Activities 1, ViewRootImpl 1, Views 63 at every checkpoint; Java heap 13.2 → 15.0 → 17.4 → 16.4 → 14.9 MB (no trend); PSS 141 → 145 MB at the end | No retention |
| Phone: update download to the installer | 4 full downloads (cache cleared) | Activities 1, Views 63; Java heap 13.9 → 13.8 MB; PSS 137.0 → 136.5 MB | No retention |
| Phone: update download with orientation toggled mid-download | 4 | Same counts; heap 14.0 → 13.8 MB. Rotation injected through `user_rotation`; Activity re-creation was not independently confirmed | No retention observed (partial coverage) |
| Phone: VPN connect attempt → stop | 10 | Main heap 13.8 → 12.8 MB; `:openvpn` PSS 49.8 → 52.8 → 53.2 → 52.6 MB; `OpenVpnService` destroyed 10/10 | Bounded; the engine process PSS rises 3 MB and plateaus (not proven to be a leak) |
| Phone: HOME → relaunch (SSE) | 10 | SSE starts 10, stops 10; Activities 1; Java heap 10.0 MB | Expected: one foreground connection |
| TV: drawer navigation | 15 × 5 screens | Activities 1, ViewRootImpl 1, Views 61; Java heap 9.2 → 10.2 → 9.2 MB | No retention |
| TV: update download | 3 | Activities 1, ViewRootImpl 1; Java heap 9.6 → 10.1 MB | No retention |

Not exercised: a fully **connected** VPN session (the public test server stayed in
`TCP_CONNECT`), pause/resume and TV VPN cycles.

Findings:

- **No retention detected; no leak verified.** No destroyed Activity, View root or service
  instance survived the cycles above, by `dumpsys meminfo` object counts after a forced GC.
  Without heap dumps this shows absence of detected retention, not a proof that none exists.
- **Bounded/expected:** the update download previously posted one UI callback per 8 KiB read
  (about 7,000 for the 57 MB APK) and each one held the Activity-backed dialog until the main
  thread drained it. That is transient, size-bounded retention, not a leak; the dispatcher now
  keeps one pending callback and removes it on dismiss. `LatestUpdateProgressDispatcherTest`
  covers it. The dialog wiring cannot be unit tested (Robolectric legacy resources cannot
  inflate the themed dialog). The device runs show the update flow completing with object
  counts back at baseline; they were taken only with the fix and do not isolate the
  dispatcher's own effect, which only the unit tests assert.
- **Inconclusive:** the 3 MB `:openvpn` PSS growth over the first five VPN cycles. It
  plateaued, but the engine's native allocations were not profiled.

## Device parameters for the scripts

The scripts default to a 1080x2400 phone running the Russian-locale debug build. Override
through environment variables (find coordinates with `adb exec-out uiautomator dump /dev/tty`):

| Variable | Meaning | Phone default | Android TV (MIBOX4, 1920x1080) |
| --- | --- | --- | --- |
| `HAM` | Drawer button `x y` | `63 206` | `48 88` |
| `ITEM_X` | Drawer item `x` | `300` | `280` |
| `ITEM_YS` | Y of Server/DNS/Filter/Settings/About | `175 301 427 553 679` | `64 160 256 352 448` |
| `UPDATE_ITEM_Y` | Y of the Update item | `805` | `544` |
| `UPDATE_BTN` | Update dialog button `x y` | `888 1378` | `1360 641` |
| `RESUMED` | Activity line to grep from `dumpsys` | ` topResumedActivity` | ` ResumedActivity` |
| `LAUNCH` | Component for `bgfg.sh` | `<pkg>/.mobile.SplashActivity` | `<pkg>/.tv.SplashActivity` (`.tv.MainActivity` is not exported and `am start` is denied) |

The update script's rotation-restoration regression test runs without a device: `bash docs/guides/memory-profiling-scripts/tests/test-update-rotation.sh`.

Keep the leading space in the `RESUMED` values; it keeps ` ResumedActivity` from matching
`mResumedActivity`. `update.sh` temporarily disables auto-rotation, then restores both original rotation settings on exit (or aborts without changes if they cannot be read). `vpn.sh` taps the
connection button at `540 2270`; adjust for another screen size.
