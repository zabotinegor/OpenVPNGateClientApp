# Orchestration Context: SUB-04

## Discovered during master-plan BA (do not re-discover)
- Affected directories: `src/tv/src/main/java/com/yahorzabotsin/openvpnclientgate/tv/` (MainActivity.kt and D-pad/drawer helpers), plus the shared screens touched by SUB-02/SUB-03 in `src/core/.../ui/serverlist/`.
- Stack markers found: existing TV D-pad focus handling (`focusAdapterPositionWhenReady`), `TvDrawerInteractionGuard` for drawer D-pad interaction. No existing long-press-equivalent pattern anywhere yet.
- Integration points: mobile and TV launchers reuse the same core Activities/Adapters (`ServerListActivity`/`CountryServersActivity`); TV-specific behavior is layered via `src/tv` wrappers and focus/key handling, not separate screens.

## Key decisions made
- TV interaction: D-pad long-press (hold OK/center) opens a dialog (not a `PopupMenu`, which doesn't anchor well on TV/D-pad). User explicitly chose this over a dedicated always-visible favorite icon/button per row.

## Dependencies from prior sub-plans
- SUB-02 output: countries screen pinned favorites section and mobile long-press dialog/menu, implemented and merged.
- SUB-03 output: servers screen pinned favorites section and mobile long-press dialog/menu, implemented and merged.
- This sub-plan only adds the TV D-pad long-press affordance on top of the already-existing pinned sections; it does not re-implement the sections themselves.

## Skip in BA step
- Full repo scan (already done).
- Stack detection (already done).
- TV launcher wiring discovery (already done — see integration points above).
