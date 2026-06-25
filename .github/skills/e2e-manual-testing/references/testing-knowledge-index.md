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

## Android VPN permission dialog (com.android.vpndialogs)

- Service repo: d:\Apps\OpenVPNClient\OpenVPNClientClientApp
- Surface: android
- When to use: First time tapping Connect after fresh install or session restart
- Reusable workaround/setup: The system shows `com.android.vpndialogs/.ConfirmDialog` which uiautomator
  CANNOT capture in the app's node tree. After tapping Connect, wait 1-2 s then dump UI from the
  `com.android.vpndialogs` package: look for "ОК" button. Typical bounds: [577,2084][991,2179],
  center (784, 2131). Tap OK to grant. Permission persists across sessions (no regrant needed).
- Source-of-truth doc: tests/manual-e2e/stories/bug-fgs-crash-rapid-reconnect-and-probe-type-erasure/suites/BUG-RRC-CORE.md
- Last validated: 2026-06-25
- Notes: Appears ONLY on first VPN connect per app install. After granting once, Connect taps go directly
  to ACTION_START. To pre-check whether it was already granted:
  `adb shell dumpsys connectivity | grep -i vpn` — if an entry for the package is present, already granted.

## Android POST_NOTIFICATIONS permission (Android 13)

- Service repo: d:\Apps\OpenVPNClient\OpenVPNClientClientApp
- Surface: android
- When to use: First tap on Connect button in a fresh install — triggers GrantPermissionsActivity before VPN flow
- Reusable workaround/setup: Grant via ADB before testing to skip dialog:
  `adb -s <serial> shell pm grant com.yahorzabotsin.openvpnclientgate android.permission.POST_NOTIFICATIONS`
  Alternatively, tap "Allow" in the GrantPermissionsActivity dialog (appears from permissioncontroller).
  The VPN permission dialog (vpndialogs) appears AFTER this one resolves.
- Source-of-truth doc: tests/manual-e2e/stories/bug-fgs-crash-rapid-reconnect-and-probe-type-erasure/suites/BUG-RRC-CORE.md
- Last validated: 2026-06-25
- Notes: `adb shell pm grant` is sufficient — no need to interact with the dialog UI.

## Seed entry
- Service repo: N/A
- Surface: N/A
- When to use: Bootstrap only
- Reusable workaround/setup: No historical entries yet.
- Source-of-truth doc: N/A
- Last validated: 2026-05-13
- Notes: Add entries after each Manual QA run when reusable knowledge is discovered.
