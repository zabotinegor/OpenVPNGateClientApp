---
id: CASE-favorites-section-appears-additive
storyId: SUB-03
surface: android
ac: [AC-1, AC-5]
---

# Pinned Favorites section appears immediately and is additive

## Preconditions
- Servers-in-country screen open; exactly one server of this country just favorited (previous case).

## Steps
1. Without any manual refresh, dump the UI (`uiautomator dump`).
2. Assert a "Favorites" section header is pinned at the top of the list, above the regular rows.
3. Assert the favorited server appears under the header.
4. Assert the SAME server also still appears at its normal position in the regular list below
   (additive pattern — total visible rows = N regular + 1 pinned duplicate).
5. Optionally confirm persistence layer: `run-as ... cat shared_prefs/favorites_prefs.xml` contains the server id.

## Expected
- Section appears instantly after the toggle (AC-5), contains only this country's favorites (AC-1),
  and the regular list still lists the favorited server (additive regression guard).
