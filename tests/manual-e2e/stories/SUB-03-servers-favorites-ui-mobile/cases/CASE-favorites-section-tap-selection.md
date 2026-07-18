---
id: CASE-favorites-section-tap-selection
storyId: SUB-03
surface: android
ac: [AC-4]
---

# Tap in favorites section selects like regular list

## Steps
1. On the servers-in-country screen with a pinned Favorites section, short-tap the favorited server's row
   inside the Favorites section.
2. Assert the activity finishes back to Main.
3. Assert Main shows the tapped server as selected (server counter index and city name match the tapped server).

## Expected
- Selection behavior identical to tapping the same server in the regular list.
