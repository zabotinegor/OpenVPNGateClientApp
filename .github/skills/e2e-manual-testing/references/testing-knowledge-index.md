# Testing Knowledge Index

Purpose: keep short, reusable, non-secret pointers discovered by Manual QA runs so future agents can find proven setup/workaround paths quickly.

Scope:
- This file is an index, not a full runbook.
- Source of truth stays in each tested service repository (for example `tests/manual-e2e/environment/*.md`).
- Keep entries concise and link-first.

Entry template:

## <Short title>
- Service repo: <owner/repo or local path>
- Surface: web | android | api | db | desktop | shell | worker | integration-service
- When to use: <trigger/symptom>
- Reusable workaround/setup: <short summary>
- Source-of-truth doc: <path in tested service repo>
- Last validated: <YYYY-MM-DD>
- Notes: <optional non-secret hints>

---

## Seed entry
- Service repo: N/A
- Surface: N/A
- When to use: Bootstrap only
- Reusable workaround/setup: No historical entries yet.
- Source-of-truth doc: N/A
- Last validated: 2026-05-13
- Notes: Add entries after each Manual QA run when reusable knowledge is discovered.

---

## SSE client: splash→MainActivity transition causes expected brief onStop
- Service repo: OpenVPNClientClientApp
- Surface: android
- When to use: Validating SSE lifecycle when app starts cold; logcat shows `SSE client stopping` before MainActivity appears
- Reusable workaround/setup: This is expected behavior — ProcessLifecycleOwner fires onStop during the brief window between SplashActivity finishing and MainActivity resuming. The SSE client restarts immediately on MainActivity onStart. Look for a second `SSE client starting` log ~1–3 s after the first stop to confirm correct lifecycle handling.
- Source-of-truth doc: tests/manual-e2e/stories/sub-02-android-sse-client/suites/SUB02-CORE.md
- Last validated: 2026-06-23
- Notes: Device screen must be on/unlocked for ProcessLifecycleOwner to fire onStart. If screen is off (locked), app stays in background state; wake with `adb shell input keyevent KEYCODE_WAKEUP && adb shell wm dismiss-keyguard`.

## WorkManager SystemJobService verification via dumpsys jobscheduler
- Service repo: OpenVPNClientClientApp
- Surface: android
- When to use: Verifying WorkManager periodic refresh is still active (e.g. AC-4 in SUB-02 SSE client story)
- Reusable workaround/setup: `adb shell dumpsys jobscheduler | grep -A10 "openvpnclientgate/androidx.work"` shows job registration, minimum latency, and last run timestamp. `dumpsys workmanager` is not available on Android 13; use jobscheduler instead.
- Source-of-truth doc: tests/manual-e2e/stories/sub-02-android-sse-client/suites/SUB02-CORE.md
- Last validated: 2026-06-23
- Notes: Look for `SystemJobService` entry with `Minimum latency` (next scheduled run) and recent START/STOP in the history block.
