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

> **Canonical answer for TV long-press.** Use `input swipe <x> <y> <x> <y> 800` — a stationary touch
> hold. Every recorded run agrees it works, it needs no device-node lookup, and it reaches the same
> `OnLongClickListener` because the row views set one listener for both touch and key paths.
>
> `sendevent` also works, but only against the **right node and scancode**: on MIBOX4 that is
> `/dev/input/event2` with `353` (`KEY_SELECT`). A run using `/dev/input/event3` with `28`
> (`KEY_ENTER`) produced nothing, which reads as "sendevent is broken" but is a wrong-node/wrong-code
> result. Always resolve both with `getevent -pl` first.
>
> This reconciles three previously conflicting write-ups. It is inferred from their own commands
> rather than re-tested — confirm on hardware next time a MIBOX4 is to hand, then delete this note.

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

Simpler alternative (verified 2026-07-13, MIBOX4/Android 9): a stationary **touch** long-press
also reliably triggers the same `View.OnLongClickListener` code path shared by touch and D-pad
long-press (`CountryListAdapter.kt` / `ServerPickerAdapter.kt` set only one listener, not
separate touch/key handlers), and needs no `sendevent`/scancode lookup:

```
adb -s <tv> shell input swipe <row_x> <row_y> <row_x> <row_y> 800
```

Use `800`-`1000` ms hold. This is the faster default when a genuine D-pad hold isn't required by
the assertion (e.g. verifying dialog styling/label/toggle behavior rather than remote-specific
input handling).

## Dialog interaction gotchas (FavoriteActionDialog)

- When the dialog opens, `select_dialog_listview` already has focus; press `KEYCODE_DPAD_CENTER`
  directly to activate the single action item. Pressing DPAD_DOWN first moves focus to the
  Cancel button — CENTER then cancels instead of toggling.
- The Cancel button label comes from the Android framework and is locale-dependent (`ОТМЕНА` on
  ru locale). As of the SUB-07 localization work (2026-07-13 verification, ru device locale) the
  favorites strings ("Добавить в избранное" / "Удалить из избранного" / "Избранное" / "Все
  страны" / "Все серверы") are fully localized in ru/pl too — do not assume English fallback.
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

## Verifying landscape lock on TV (no `mRotation=` field on Android 9)

Unlike phone/API 26+ devices, MIBOX4's Android 9 `adb shell dumpsys window displays` output has no
`mRotation=` field at all (its `Display:` block only shows `init=1920x1080 ... cur=1920x1080`, a
fixed physical landscape resolution — TV hardware doesn't rotate). Forcing `user_rotation`/
`accelerometer_rotation` the way you would on a phone is meaningless here. A pure screenshot check
also has a blind spot: on a physically-fixed-landscape panel, an activity with no orientation
request at all renders identically to one explicitly locked to landscape, so a screenshot alone
can't distinguish "the lock is working" from "the lock silently failed and the hardware just
happens to always be landscape anyway."

`adb shell dumpsys activity activities` does **not** help here either — confirmed live against this
MIBOX4 (API 28): its output contains no `mOrientation=`/`requestedOrientation` field anywhere, for
any app, in any format.

`adb shell dumpsys window` **does** expose a genuine per-activity requested-orientation signal,
confirmed live on this hardware. Each app window's entry contains a `task={... ActivityRecord{...
<package>/<Activity> ...}}}` line immediately followed by `mOrientation=<int>`, using the standard
`ActivityInfo.SCREEN_ORIENTATION_*` integer codes (`-1` = `UNSPECIFIED`, `0` = `LANDSCAPE`, `1` =
`PORTRAIT`, `6` = `SENSOR_LANDSCAPE`, etc.). This reflects the actual `requestedOrientation` in
effect — including CoreApp's runtime `registerOrientationPolicy()` override, not just the manifest's
static `android:screenOrientation` — verified by cross-checking `.tv.MainActivity` (manifest:
`android:screenOrientation="landscape"`) and getting back `mOrientation=0` (`LANDSCAPE`):

```
adb shell dumpsys window | grep -A1 "task={.*ActivityRecord{.*<package>/<Activity>" | grep -o "mOrientation=[0-9-]*"
```

Expect `mOrientation=0` (or another landscape-family code such as `6`/`8`/`11`) for a landscape-locked
screen. This is the authoritative check — run it for each screen under test. Note it reads the
window's last-known state, so launch/navigate to the activity under test immediately before running
it.

Follow up with a screenshot (`adb shell screencap -p /sdcard/x.png && adb pull /sdcard/x.png`,
remember `MSYS_NO_PATHCONV=1` in Git Bash) as a complementary visual sanity check — the same method
already used for the pre-existing MainActivity/ServerListActivity TV evidence — and, optionally,
`adb logcat | grep -i "orientation policy"` to confirm CoreApp's lifecycle callback executed without
exception (`Orientation policy lifecycle observer registered`, no `Failed to apply orientation
policy for <ActivityName>` warning). That logcat signal only proves the callback ran without
throwing, not that the OS applied the value — the `dumpsys window` check above is what actually
proves the requested-orientation state.

## Navigation drawer open/closed state persists across activity back-navigation

Same behavior as the mobile runbook: opening the drawer, launching a secondary activity from it, then
pressing D-pad Back returns to `MainActivity` with the drawer already open and focus on the just-used
nav item (content-desc reads "Закрыть панель навигации" = "close"). Dump the UI to check before
sending more D-pad presses aimed at "opening" it again.

## Favorites prefs inspection (debug builds)

Same as mobile: `run-as com.yahorzabotsin.openvpnclientgate cat shared_prefs/favorites_prefs.xml`
(`favorite_country_codes`, `favorite_server_ids`). Restore pre-test contents at cleanup by
toggling through the UI (preferred — exercises the code path) or `pm clear` for a full reset.
