# Android TV (MIBOX4) — D-pad Manual QA Runbook

Target class: Android TV over adb Wi-Fi (`<TV_IP:5555>`, recorded locally in AGENTS.local.md).
Model: MIBOX4, Android 9, Leanback launcher. Package: `com.yahorzabotsin.openvpnclientgate`
(TV launcher module `src/tv`).

## Launching the app

The LAUNCHER category does not resolve on TV builds — use the Leanback category:

```
adb -s <tv> shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LEANBACK_LAUNCHER 1
```

Splash (`tv.SplashActivity`) transitions to `tv.MainActivity` in ~8 s. Confirm with
`adb shell "dumpsys window | grep mCurrentFocus"`.

## D-pad long-press: `input keyevent --longpress` DOES NOT WORK

On MIBOX4 (Android 9), both `input keyevent --longpress KEYCODE_DPAD_CENTER` and
`input keyevent --longpress 23` are delivered as a SHORT press (the row click fires, e.g. the
countries row navigates instead of opening the favorite dialog). The injected event pair does not
hold the key long enough for `View`'s confirm-key long-click timeout.

Workaround — inject a genuinely held key via `sendevent` on the remote's input device:

```
# Find the remote: getevent -pl  ->  "Xiaomi RC" = /dev/input/event2, KEY_SELECT = scancode 353 (DPAD_CENTER)
adb -s <tv> shell "sendevent /dev/input/event2 1 353 1 && sendevent /dev/input/event2 0 0 0 && sleep 1.2 && sendevent /dev/input/event2 1 353 0 && sendevent /dev/input/event2 0 0 0"
```

1.2 s hold reliably triggers `performLongClick` on the focused row. Re-run `getevent -pl` on other
TV hardware to find the equivalent device/scancode.

## Dialog interaction gotchas (FavoriteActionDialog)

- When the dialog opens, `select_dialog_listview` already has focus; press `KEYCODE_DPAD_CENTER`
  directly to activate the single action item. Pressing DPAD_DOWN first moves focus to the
  Cancel button — CENTER then cancels instead of toggling.
- The Cancel button label comes from the Android framework and is locale-dependent (`ОТМЕНА` on
  ru locale); favorites strings themselves render in English ("Add to favorites",
  "Remove from favorites", "Favorites") because the app has no ru/pl translations for them.
- After a favorite toggle the list refreshes and focus jumps to the toolbar back button; D-pad DOWN
  re-enters the list at the first row.

## UI verification

`uiautomator dump` + parse works fine on this TV. Useful assertions:

- Dialog present: node `id/alertTitle` (title = row name), `android:id/text1` (action label),
  `android:id/button2` (Cancel).
- No PopupMenu on TV: assert no `PopupWindow`/`ListPopupWindow` container class in the dump.
- Pinned section: `id/section_header_title` text "Favorites" with `focusable=false`; on TV the
  countries/servers lists show only the Favorites header (no "All ..." header node in the dump).
- Focus tracking: the focused row is the `CardView` with `focused="true"`; correlate with child
  `country_name`/`server_title` bounds.

## Favorites prefs inspection (debug builds)

Same as mobile: `run-as com.yahorzabotsin.openvpnclientgate cat shared_prefs/favorites_prefs.xml`
(`favorite_country_codes`, `favorite_server_ids`). Restore pre-test contents at cleanup by
toggling through the UI (preferred — exercises the code path) or `pm clear` for a full reset.
