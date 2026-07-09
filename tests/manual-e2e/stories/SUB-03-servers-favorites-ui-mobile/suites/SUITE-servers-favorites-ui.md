---
id: SUITE-servers-favorites-ui
storyId: SUB-03
surfaces: [android]
---

# Suite: Servers-in-country favorites UI (mobile touch)

Execute sequentially on a real Android phone with the mobile debug build installed.

1. CASE-long-press-toggle-menu (AC-3; also creates the favorite used by later cases)
2. CASE-favorites-section-appears-additive (AC-1, AC-5, additive regression)
3. CASE-favorites-persist-across-restart (persistence regression)
4. CASE-favorites-section-tap-selection (AC-4)
5. CASE-regular-list-tap-unchanged (AC-6)
6. CASE-favorites-section-hidden-when-empty (AC-2, AC-5; also cleanup — leaves favorites empty)

Global assertions per run: no FATAL / AndroidRuntime exception / WindowLeaked / BadTokenException / ANR in
`adb logcat -d` scan; single app PID throughout.
