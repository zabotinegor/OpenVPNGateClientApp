# Engine Update Smoke-Test Checklist

## Purpose

A reusable smoke-test checklist to run after any OpenVPN engine submodule bump (see the "OpenVPN
Engine Update Workflow" section of `AGENTS.md`), to catch regressions across core app flows before
trusting the merge. This is a template — re-run it fresh against the specific device/branch/build
under test each time; don't treat any prior run's specific evidence as still valid for a new bump.

## Scope

| Area | Regression risk if skipped |
| --- | --- |
| Cold launch, splash → main transition | Engine init crash |
| Server list load (cache and/or network) | Server parsing regressions from engine-adjacent changes |
| VPN connect + watchdog health + disconnect | Core tunnel regressions — the highest-value check |
| Notification tap → MainActivity | Notification intent regression (see `BUG-1`/notification-tap-crash history — this exact flow has regressed before) |
| Full-session stability | No fatal exceptions/ANRs across the whole session |

## Checklist

1. **Cold launch**: fresh install, force-stop if already installed, launch via
   `adb shell am start -W -n <package>/.mobile.SplashActivity` with the device unlocked. Confirm
   `LaunchState: COLD` and that `topResumedActivity` becomes `MainActivity` shortly after. No
   `FATAL EXCEPTION` in logcat.
2. **Server list load**: confirm the server list populates (cache hit or fresh fetch, either is
   fine) and the selected country/server display renders correctly (country name, address/city
   line, ping if applicable). No `JsonSyntaxException` or parse errors in logcat.
3. **VPN connect + watchdog + disconnect**: connect, confirm `LEVEL_CONNECTED`/`CONNECTED` is
   reached and the watchdog reports healthy with nonzero traffic delta. Disconnect via the user
   Stop action and confirm a clean `DISCONNECTING → DISCONNECTED` transition.
4. **Notification tap**: while connected, background the app, open the notification shade, and tap
   the VPN notification row. Confirm `topResumedActivity` becomes `MainActivity` (not a stray
   `SplashActivity` disrupting the existing task) and that VPN state is undisturbed by the tap. No
   crash — this is a direct regression check for the fixed notification-tap crash bug.
5. **No fatal exceptions across the full session**: after covering the above, grep the full-session
   logcat for `FATAL EXCEPTION|FATAL|crash|RuntimeException|NullPointerException` and confirm no
   matches across install, launch, server load, permission grant, connect/disconnect cycles, and
   notification interaction.

## Pass Criteria

- No `FATAL EXCEPTION` / uncaught `RuntimeException` anywhere in the session's logcat.
- `LEVEL_CONNECTED`/`CONNECTED` reached with the watchdog reporting healthy (nonzero traffic delta).
- `DISCONNECTING → DISCONNECTED` completes cleanly on user-initiated stop.
- `topResumedActivity` is the app's `MainActivity` after a notification tap while connected.

## Notes

- If the engine bump raises the engine module's `compileSdk`/`targetSdk`, the first build on a
  machine without that SDK Platform installed can fail with
  `Failed to find target with hash string 'android-NN'` — this is a build prerequisite, not a smoke
  failure; see `docs/guides/troubleshooting.md` for the retry/explicit-install guidance.
- `testDebugUnitTestApp` does not exercise the engine module's own unit tests — run
  `./gradlew :openVpnEngine:testFullDebugUnitTest` directly when the merged upstream commits add or
  change engine-side tests (see `docs/guides/how-to.md`).

## Related Documents

- `docs/INDEX.md` — knowledge-base catalog
- `AGENTS.md` — "OpenVPN Engine Update Workflow" section (the workflow this checklist supports)
- `docs/guides/troubleshooting.md`, `docs/guides/how-to.md` — engine-bump-specific gotchas

---

*Last verified against: the `US-14` engine bump (`a83da9ff -> 764b6b70`) and its SDK-platform-37 gotcha (2026-07-25).*
