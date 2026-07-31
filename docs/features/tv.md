# TV Drawer Interaction Guard

## Scope

This document describes the interaction-isolation guard on the Android TV main screen: while the
navigation drawer is opening, open, or closing, only drawer-focused input is allowed — the main
screen's connection controls (`start_connection_button`, `pause_connection_button`, server
selector) must never receive an accidental OK/confirm action from a drawer transition.

## Business Rule

- While the drawer is opening or closing (`DrawerLayout` state not `STATE_IDLE`) or fully open,
  OK/confirm key events must not reach main-screen action controls.
- When the drawer is open, OK applies only to drawer-focused items.
- After the drawer closes, the primary connection control regains focus and interactivity — but a
  single immediate accidental OK on main content right after close is suppressed once by a
  post-close debounce guard (covers the common case of a user's repeated OK presses during
  closing landing just after the drawer is reported closed).
- Normal drawer navigation (opening/closing via D-pad, item selection) must keep working while the
  guard is active — the guard only blocks *main-content* actions during the transition, not drawer
  interaction itself.

## Source of Truth

`src/tv/src/main/java/com/yahorzabotsin/openvpnclientgate/tv/TvDrawerInteractionGuard.kt` — a pure
`internal object` with no Android framework dependencies beyond `KeyEvent`/`DrawerLayout` constants,
so its decision logic is fully unit-testable:

| Function | Purpose |
| --- | --- |
| `isOkKey(keyCode)` | Recognizes `KEYCODE_DPAD_CENTER`, `KEYCODE_ENTER`, `KEYCODE_NUMPAD_ENTER` as the confirm key. |
| `shouldBlockMainContent(drawerState, isDrawerOpen)` | True whenever the drawer is not idle (mid-transition) or fully open — the main-content blocking condition. |
| `shouldConsumeOkEvent(keyCode, keyAction, drawerState, isDrawerEngaged, isFocusInDrawer)` | Decides whether an OK key event should be consumed (blocked from reaching main content) during an active/engaged drawer state. |
| `shouldConsumeDebouncedOkEvent(...)` | The post-close debounce: consumes exactly one OK event on main content immediately after the drawer closes, gated by `isCloseDebounceActive`/`hasConsumedPostCloseOkUp` so it only fires once per close. |
| `shouldArmBurstGuardAfterDebouncedConsume(keyCode, keyAction)` | Arms the burst guard after a debounced consume, to avoid a second accidental action from a rapid key-repeat burst. |
| `shouldRequestDrawerFocus(slideOffset)` | True once the drawer has started sliding open (`slideOffset > 0f`), used to move D-pad focus into the drawer as soon as it begins opening. |

The Activity wires these pure functions to `DrawerLayout`'s open/close/slide callbacks and to its
`dispatchKeyEvent` override; the guard object itself holds no state and takes all context as
parameters.

## Regression Coverage

- `src/tv/src/test/java/com/yahorzabotsin/openvpnclientgate/tv/TvDrawerInteractionGuardTest.kt` —
  unit tests directly against the pure decision functions above.
- `src/tv/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/tv/MainActivityTvDrawerGuardTest.kt` —
  instrumented Espresso test exercising the real drawer/main-screen interaction end to end. This is
  the authoritative coverage for real interaction timing (see below) — the manual QA case that
  originally validated this behavior (`TV-DRAWER-FALSE-CLICK-001`) has been retired now that this
  instrumented test exists.

## QA Gotchas Worth Keeping

- **ADB key injection is much slower than a real remote.** `adb shell input keyevent` dispatches
  with roughly a 380ms wait per key on MIBOX4-class hardware, versus ~100ms/key for a real user
  mashing the remote. The guard is designed for real-user speeds; ADB-based manual spam-testing can
  under-stress the guard compared to a real device. Treat the Espresso suite as authoritative for
  timing-sensitive verification, not ad hoc ADB key spam.
- **`com.android.vpndialogs` must be force-stopped before and after manual runs**
  (`adb shell am force-stop com.android.vpndialogs`). This system package's VPN permission dialog
  can persist across force-stops of the main app and otherwise causes false failures/confusing
  state when manually retesting drawer/connect interactions.
- If VPN is already authorized on the device, the system `ConfirmDialog` won't appear even for a
  legitimate button press — don't mistake "no dialog" for "guard blocked the action" when manually
  spot-checking; rely on the Espresso suite's explicit assertions instead.

## Related Documents

- `docs/INDEX.md` — knowledge-base catalog
- `CLAUDE.md` — architecture overview and entry points

---

*Last verified against: `TvDrawerInteractionGuard.kt` + its unit/instrumented tests (2026-07-25).*

---

## Android TV surface beyond the drawer guard

The TV launcher is a genuinely different surface, not a resized phone build.

| | mobile | tv |
|---|---|---|
| Required feature | — | `android.software.leanback` |
| Touchscreen | required | **not** required |
| Launcher category | `LAUNCHER` | `LEANBACK_LAUNCHER` |
| Orientation | unrestricted | `landscape`, both activities |
| `allowBackup` | `true` | `false` |
| Banner drawable | — | present |
| `MainActivity` size | ~6 lines | ~212 lines (drawer + D-pad focus handling) |

Both launchers share one `applicationId`, which is intentional — splitting it has VPN-permission and
signing consequences.

**Launching on TV via ADB is different.** The `LAUNCHER` category does not resolve; use
`LEANBACK_LAUNCHER`. Commands are in [../guides/adb-cookbook.md](../guides/adb-cookbook.md).

**Focus, not touch.** `ui/common/utils/TvUtils` gates TV-specific behaviour, including focusing the
first item so the remote has somewhere to land. A list that works by tap on phone can be unreachable
on TV if nothing takes initial focus.

Instrumented tests target the Leanback variant through `connectedDebugAndroidTestTv`; device-specific
gotchas are in [../operations/device-qa-tv.md](../operations/device-qa-tv.md).

*Last verified against: `tv/MainActivity.kt`, `ui/common/utils/TvUtils.kt`, `src/tv` manifest and `build.gradle.kts` (2026-07-31).*
