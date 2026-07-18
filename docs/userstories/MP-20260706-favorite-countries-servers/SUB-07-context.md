# Orchestration Context: SUB-07

## Discovered during master-plan BA (do not re-discover)
- Affected files: `src/core/src/main/res/values/strings.xml` (source of truth, lines ~31-35: `favorites_section_title`, `favorites_add_action`, `favorites_remove_action`, `favorites_added_toast`, `favorites_removed_toast`).
- Confirmed missing: `grep -n "favorite" src/core/src/main/res/values-ru/strings.xml` and the `values-pl` equivalent both return zero matches — these 5 keys were added in SUB-02 but never translated.
- Supported locales in this repo: `values` (default/English), `values-ru`, `values-pl` (plus `values-night`/`values-v31` which are theme/API qualifiers, not language locales — no translation needed there).

## Key decisions made
- Scope limited to exactly these 5 keys and exactly the 2 existing non-default locales (`ru`, `pl`) — not a general localization audit.

## Dependencies from prior sub-plans
- SUB-02/SUB-03 output: the 5 `favorites_*` string keys already exist in default `values/strings.xml` and are referenced from `ServerListActivity`/`CountryServersActivity`/ViewModels — this sub-plan only adds translated values, it does not add or rename keys.
- Independent of SUB-06 (framing) and SUB-08 (dialog theming) — different files, can run in parallel.

## Skip in BA step
- Full repo scan (already done).
- Locale/resource file discovery (already done — see affected files above).
