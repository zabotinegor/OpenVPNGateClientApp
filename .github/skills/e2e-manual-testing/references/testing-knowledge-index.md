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

## Android ADB — OpenVPN Gate Client QA setup and workarounds
- Service repo: OpenVPNClientClientApp (local)
- Surface: android
- When to use: Any manual QA run on device R58N849XQEY (MIUI/Xiaomi secondary space)
- Reusable workaround/setup: `pm list packages` fails with SecurityException — use `dumpsys package` instead; launch via `.mobile.SplashActivity`; wake screen with `input keyevent 224` if splash stalls; use `--tests` flag workaround (run full suite instead of filtered); force-stop and pm clear patterns documented.
- Source-of-truth doc: tests/manual-e2e/environment/android-adb-vpn-qa-runbook.md
- Last validated: 2026-06-26
- Notes: configData strings from the API are dynamic (change each fetch); this causes stale config in ViewModel to mismatch the store after SSE syncs, resulting in `matched by ip index=1/N` in logs. Belarus (3 servers, all IP 213.184.224.127) is the only known country exercising multi-server same-IP logic.

## OpenVPN Gate configData instability — dynamic config strings
- Service repo: OpenVPNClientClientApp (local)
- Surface: android
- When to use: Any test that selects server N/N, waits >5 seconds (SSE sync fires), then taps Connect
- Reusable workaround/setup: The config string returned by the OpenVPN Gate API changes on each fetch. After the first SSE sync post-selection, the store has new config strings. Any code path comparing ViewModel-held configData against the store's configData will mismatch and fall back to IP-only matching, resetting the index to 0 (server 1/N). Tap Connect WITHIN the same SSE fetch cycle (<3 seconds after selection) to avoid this.
- Source-of-truth doc: tests/manual-e2e/environment/android-adb-vpn-qa-runbook.md
- Last validated: 2026-06-26
- Notes: Root cause of AC1/AC2 failures in BUG-server-counter-resets-on-connect QA run post-review-fix. Filed as Defect C.
