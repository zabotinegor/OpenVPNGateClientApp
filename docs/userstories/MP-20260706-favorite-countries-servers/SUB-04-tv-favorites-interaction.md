# SUB-04: TV D-pad favorites interaction

## Scope boundary
Adapt the favorites pinned sections (countries screen and servers screen, delivered by SUB-02/SUB-03) for Android TV: a D-pad long-press (hold OK/center) on a focused row opens a TV-appropriate dialog with add/remove-favorite, consistent with existing TV D-pad focus/drawer patterns.

## Acceptance criteria
1. On TV, holding the D-pad center/OK button on a focused country row or server row opens a dialog offering add/remove-favorite reflecting current favorite state.
2. The favorites pinned section (from SUB-02/SUB-03) is focusable and navigable via D-pad on both the countries and servers screens, following existing TV focus-order conventions (e.g. `focusAdapterPositionWhenReady`).
3. Existing TV D-pad navigation, drawer interaction (`TvDrawerInteractionGuard`), and short-press select/connect behavior are unchanged.
4. No `PopupMenu`-anchored UI (mobile pattern) is used on TV; the TV dialog pattern is self-contained and remote-navigable.
5. Behavior is verified on both the countries screen and the servers-in-country screen.

## Out of scope
- Any change to mobile long-press behavior (SUB-02/SUB-03 already deliver that).
- New TV-only screens beyond the pinned sections.

## dependsOn
SUB-02, SUB-03
