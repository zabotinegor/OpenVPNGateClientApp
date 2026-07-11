# SUB-07: Missing localizations for favorites strings

## Scope boundary
Add the missing translations for the 5 favorites-related string resources (`favorites_section_title`, `favorites_add_action`, `favorites_remove_action`, `favorites_added_toast`, `favorites_removed_toast`) to every locale the app already supports besides the default, so favorites UI text is not shown in English on non-English devices.

## Acceptance criteria
1. `src/core/src/main/res/values-ru/strings.xml` contains translated entries for all 5 `favorites_*` keys, matching the existing translation tone/style of neighboring strings in that file.
2. `src/core/src/main/res/values-pl/strings.xml` contains translated entries for all 5 `favorites_*` keys, matching the existing translation tone/style of neighboring strings in that file.
3. No key is duplicated, misspelled, or left as a copy-paste of the English default.
4. The default `values/strings.xml` entries are unchanged.
5. App builds successfully with the new resources (`assembleDebugApp` or equivalent lint/resource validation) and no missing-translation lint warnings are introduced for these keys.

## Out of scope
- Any new locales beyond `ru` and `pl` (the app's current supported set).
- Any change to favorites UI layout, behavior, or dialog styling (SUB-06, SUB-08).
- Any other pre-existing missing translations unrelated to favorites.

## dependsOn
None (independent of SUB-06 and SUB-08; can run in parallel with them).
