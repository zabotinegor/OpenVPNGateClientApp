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

## MIUI uiautomator noise and source-switch validation flow
- Service repo: d:/Apps/OpenVPNClient/OpenVPNClientClientApp
- Surface: android
- When to use: ADB-driven Android manual QA on MIUI where uiautomator output is noisy or source-switch checks are flaky.
- Reusable workaround/setup: Treat MIUI theme_compatibility stack traces as non-fatal when UI XML is still produced; prefer XML artifact inspection and log-based source markers for DEFAULT_V2, LEGACY, and VPNGATE assertions.
- Source-of-truth doc: tests/manual-e2e/environment/android-miui-manual-qa-notes.md
- Last validated: 2026-05-19
- Notes: This run validated that direct XML reads are more reliable than terminal regex parsing under noisy MIUI dumps.
