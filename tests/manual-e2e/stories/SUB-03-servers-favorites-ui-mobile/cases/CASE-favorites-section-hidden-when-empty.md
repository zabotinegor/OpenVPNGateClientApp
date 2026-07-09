---
id: CASE-favorites-section-hidden-when-empty
storyId: SUB-03
surface: android
ac: [AC-2, AC-5]
---

# Favorites section hidden when empty (and removal is immediate)

## Steps
1. On the servers-in-country screen, long-press the favorited row in the pinned Favorites section.
2. Tap "Remove from favorites".
3. Without manual refresh, dump the UI.
4. Assert the "Favorites" header is gone and the regular list shows each server exactly once.
5. Assert `shared_prefs/favorites_prefs.xml` has an empty `favorite_server_ids` set (cleanup verified).

## Expected
- Section disappears instantly (AC-5); regular list renders unchanged with no duplicates (AC-2).
