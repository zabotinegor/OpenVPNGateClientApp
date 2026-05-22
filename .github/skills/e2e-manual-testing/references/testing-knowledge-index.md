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

## MIUI pause-script evidence gap
- Service repo: zabotinegor/OpenVPNGateClientApp
- Surface: android
- When to use: `run-mobile-pause-button-qa.ps1` returns PASS but expected screenshots are missing.
- Reusable workaround/setup: Validate evidence file presence after the run and recapture required checkpoints with `adb exec-out screencap -p` when `/sdcard/<name>.png` pull fails.
- Source-of-truth doc: `tests/manual-e2e/environment/android-miui-manual-qa-notes.md`
- Last validated: 2026-05-20
- Notes: MIUI may emit `theme_compatibility.xml` noise during UI dumps in the same run; treat separately from screenshot capture status.

## MIUI pause-suite logcat filter gap
- Service repo: zabotinegor/OpenVPNGateClientApp
- Surface: android
- When to use: `run-mobile-pause-button-qa.ps1` produces a QA report but `logcat-suite.txt` has only header lines.
- Reusable workaround/setup: Keep suite report as primary result, then run an additional unfiltered `adb logcat -d -t <N>` capture for watchdog/lifecycle diagnostics.
- Source-of-truth doc: `tests/manual-e2e/environment/android-miui-manual-qa-notes.md`
- Last validated: 2026-05-20
- Notes: MIUI stderr can be saturated by `theme_compatibility.xml` noise while filtered log tags return no app lines.

## US-10 phase-2 degradation too strong
- Service repo: zabotinegor/OpenVPNGateClientApp
- Surface: android
- When to use: US-10 watchdog retest needs a false-connected degradation path on Mi 9 SE.
- Reusable workaround/setup: `svc wifi/data disable` and a bogus `http_proxy` both tore down the tunnel before watchdog markers appeared; use a weaker probe-only block that keeps the VPN connected.
- Source-of-truth doc: `tests/manual-e2e/environment/android-miui-manual-qa-notes.md`
- Last validated: 2026-05-20
- Notes: The simple radio/proxy disable paths are not sufficient for MQ-US10-002..004 on this device.

## MIUI phase-2 script UI-dump hang
- Service repo: zabotinegor/OpenVPNGateClientApp
- Surface: android
- When to use: `run-phase2-watchdog.ps1` keeps printing MIUI `theme_compatibility.xml` exceptions and does not produce output artifacts.
- Reusable workaround/setup: avoid hard dependency on `uiautomator dump` polling for readiness; prefer `dumpsys activity` checks and fail fast when the output folder stays empty after timeout.
- Source-of-truth doc: `tests/manual-e2e/environment/android-miui-manual-qa-notes.md`
- Last validated: 2026-05-20
- Notes: Observed during US-10 rerun-6 on Mi 9 SE with successful APK install but stalled script execution.

## US-10 phase-2 baseline server fallback
- Service repo: zabotinegor/OpenVPNGateClientApp
- Surface: android
- When to use: US-10 phase-2 watchdog retest reaches the main screen but never gets a CONNECTED baseline.
- Reusable workaround/setup: use the log-driven script and the validated connect tap `input tap 540 2100`, but confirm the selected server in logcat before the 180s wait; on the latest retest the app still fell back to Australia `202.65.78.119` and disconnected before any watchdog markers appeared.
- Source-of-truth doc: `tests/manual-e2e/environment/android-miui-manual-qa-notes.md`
- Last validated: 2026-05-21
- Notes: Country selection by touch was flaky on this MIUI device; V2 server list loading worked, but the baseline server still did not reach CONNECTED.

## MIUI stale-stop pref simulation write path
- Service repo: zabotinegor/OpenVPNGateClientApp
- Surface: android
- When to use: Manual QA needs to simulate stale `pending_stop_intent` for stop/start carry-over validation and `run-as ... sh -c "cat ... > shared_prefs/..."` fails.
- Reusable workaround/setup: use `run-as ... tee /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/vpn_stop_teardown.xml` and verify via `run-as ... cat shared_prefs/vpn_stop_teardown.xml`; avoid fragile chained wrappers that can miscompose `adb shell am` calls.
- Source-of-truth doc: `tests/manual-e2e/environment/android-miui-manual-qa-notes.md`
- Last validated: 2026-05-21
- Notes: Observed during US-10 stale pending-stop remediation QA on Mi 9 SE.

## MIUI host-reachable endpoint but in-app connect failure
- Service repo: zabotinegor/OpenVPNGateClientApp
- Surface: android
- When to use: US-10 rerun shows repeated connect failure (`pause_connection_button` never appears) while the selected endpoint is unchanged.
- Reusable workaround/setup: verify external reachability first with `Test-NetConnection 124.150.75.98 -Port 1940`; if reachable but app still cannot transition to connected controls, classify as app/runtime defect evidence instead of pure endpoint outage and keep rerun marked failed.
- Source-of-truth doc: `tests/manual-e2e/environment/android-miui-manual-qa-notes.md`
- Last validated: 2026-05-22
- Notes: Observed on Mi 9 SE during base-main US-10 rerun where stale-stop phase also stalled before summary emission.

## US-11 notification tap MIUI readiness blockers
- Service repo: zabotinegor/OpenVPNGateClientApp
- Surface: android
- When to use: US-11 notification-tap validation blocks before MQ execution on Mi 9 SE.
- Reusable workaround/setup: dismiss startup update dialog (`android:id/button2`) before connect flow; if notification-shade `uiautomator dump` returns `could not get idle state`, use screenshot + fixed notification tap fallback and confirm app foreground by `dumpsys activity activities`.
- Source-of-truth doc: `tests/manual-e2e/environment/android-miui-manual-qa-notes.md`
- Last validated: 2026-05-22
- Notes: Used in US-11 run `manual-qa/2026-05-22-us11-notification-tap-fix/runs/20260522-205126`.
