# MQ-US15-005 — Migration of stale persisted server_source values (AC4)

## Preconditions
- Debug build (debuggable, run-as accessible)
- App installed at commit e427d55

## Steps (repeated for each of 3 stale values: `LEGACY`, `CUSTOM`, legacy `"DEFAULT"` string)
1. `adb shell am force-stop com.yahorzabotsin.openvpnclientgate`
2. `adb shell run-as com.yahorzabotsin.openvpnclientgate sh -c "sed -i 's/<prev>/<value>/' shared_prefs/user_settings.xml"`
   to set `server_source` to the target stale value
3. `adb logcat -c`
4. `adb shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LAUNCHER 1`
5. Wait for launch, confirm `dumpsys window | grep mCurrentFocus` reaches `MainActivity` (not stuck
   on Splash, no crash dialog)
6. `adb logcat -d | grep -iE "FATAL EXCEPTION"` — confirm empty
7. For the `LEGACY` case, additionally open Settings → Server list source and confirm "Client for
   OpenVPN Gate" is shown selected

## Expected
- Clean launch to MainActivity for all 3 stale values, no crash, no unresolved-enum error
- Settings reflects "Client for OpenVPN Gate" as the resolved/migrated selection

## Result: PASS (all 3 sub-cases)
- `LEGACY`: launched cleanly to MainActivity; Settings confirmed "Client for OpenVPN Gate" selected;
  zero FATAL EXCEPTION in logcat.
- `CUSTOM`: launched cleanly to MainActivity; zero FATAL EXCEPTION in logcat.
- Legacy `"DEFAULT"` string: launched cleanly to MainActivity; zero FATAL EXCEPTION in logcat.
- Source code confirms this is a deliberate in-memory-only resolution
  (`UserSettingsStore.kt:50-56`): `load()` maps `"DEFAULT"`, `"LEGACY"`, `"CUSTOM"` to
  `ServerSource.DEFAULT_V2` on every read without rewriting the persisted file — idempotent and
  safe on every subsequent launch, not a defect.
- Device state restored to `DEFAULT_V2` after the test (cleanup).

## Evidence
- Command transcripts and `mCurrentFocus`/logcat greps captured in session log.

## Run date
2026-07-20
