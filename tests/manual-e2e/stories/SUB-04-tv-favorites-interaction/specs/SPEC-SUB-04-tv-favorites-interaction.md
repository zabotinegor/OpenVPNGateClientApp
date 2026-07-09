---
id: SPEC-SUB-04-tv-favorites-interaction
title: TV D-pad favorites interaction specification
relatedSuite: SUITE-SUB-04-tv-favorites-interaction
surface: android-tv
story: docs/userstories/MP-20260706-favorite-countries-servers/SUB-04-tv-favorites-interaction.md
---

## Business Context

SUB-02/SUB-03 delivered pinned Favorites sections with a long-press PopupMenu on mobile touch
devices. PopupMenu does not anchor or focus well with D-pad navigation, so on Android TV a
long-press of the D-pad center/OK key on a focused row must open a self-contained, remote-navigable
AlertDialog (`FavoriteActionDialog`) instead. Short-press select/connect, drawer interaction, and
existing D-pad focus behavior must remain unchanged.

## Acceptance Criteria Mapping

- AC1: Holding OK/center on a focused country row or server row opens a dialog whose title is the
  row name and whose single action item reflects current favorite state (Add/Remove from
  favorites) plus a Cancel button. -> CASE-SUB04-001, CASE-SUB04-002, CASE-SUB04-004
- AC2: The pinned Favorites section rows are D-pad focusable/navigable; section headers are
  skipped by focus. -> CASE-SUB04-003
- AC3: Existing TV D-pad navigation, drawer interaction, and short-press select/connect behavior
  are unchanged. -> CASE-SUB04-005
- AC4: No PopupMenu appears anywhere on TV; the dialog is fully remote-navigable and BACK
  dismisses it without action. -> CASE-SUB04-001, CASE-SUB04-002, CASE-SUB04-004
- AC5: Behavior is verified on BOTH the countries screen (ServerListActivity) and the
  servers-in-country screen (CountryServersActivity). -> CASE-SUB04-001 vs CASE-SUB04-002/004

## Evidence Model

- uiautomator dumps proving dialog presence, title, action label, and Cancel button.
- uiautomator dumps proving focus position on pinned Favorites rows and header skip.
- Full-session logcat scan: no FATAL, ANR, WindowLeaked, or BadTokenException.
- Installed build fingerprint (versionCode/lastUpdateTime) matching the commit under test.

## Device Baseline

- Target device: Android TV over adb at <TV_IP:5555> (do not store the real IP in repo files;
  it is recorded locally in AGENTS.local.md).
- Model: MIBOX4, Android 9, Leanback launcher.
- Package: `com.yahorzabotsin.openvpnclientgate` (shared app id, TV launcher module `src/tv`).

## Residual Risks Watched During Execution

- Dialog title may show IP instead of server name for blank-city non-V2 servers (cosmetic).
- Dialog dismisses on Activity recreation (accepted).
- No isFinishing guard before show() — watch logcat for BadTokenException (accepted risk class).
