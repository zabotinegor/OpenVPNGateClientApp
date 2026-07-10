---
id: CASE-SUB05-005
title: Favorited country/server disappears from the pinned section when absent from a sync and reappears automatically when present again
surface: android-mobile
suite: SUITE-SUB-05-favorites-manual-e2e
acceptance: AC3 (mid-state also evidences AC4)
---

## Background

The pinned Favorites section shows only **currently available** favorites:
`FavoritesFilter.filterFavoriteCountries(...)` / `FavoritesFilter.filterFavoriteServers(...)`
intersect the stored favorite set with the latest synced list. When a favorited country/server is absent from a sync it is hidden from the
pinned section but its entry **stays in `favorites_prefs.xml`**; when a later sync contains it
again it reappears automatically with no re-favoriting. See
[src/docs/favorites-ui-patterns.md](../../../../../src/docs/favorites-ui-patterns.md)
("Availability Filtering") and
[src/docs/server-sync-flow.md](../../../../../src/docs/server-sync-flow.md) (trigger matrix).

## Preconditions

- Same phone build/launch preconditions as CASE-SUB05-001.
- At least one favorite country pinned on the countries screen (reuse from CASE-SUB05-001 or add
  one now); optionally one favorite server in a target country for the server-level variant.
- Pre-test `favorites_prefs.xml` recorded — this exact content must remain byte-identical for the
  whole case (the point of AC3 is that storage never changes).

## Sync trigger options (setup notes)

The list content only changes when a sync fetches different backend content. Pick whichever
trigger is practical for the session, in order of preference:

1. **Controlled backend churn (preferred, deterministic)**: if the local backend from
   AGENTS.local.md is available and the app points at it, remove the favorited country's servers
   (or the favorited server) from the backend data, let it push an SSE `servers-changed` event
   (or restart its list), then re-add them for the restore half. SSE-driven sync applies within
   seconds while the app is foregrounded (500 ms debounce). If no mutable local backend is
   available, a local mock backend serving captured real payloads works the same way — see
   [docs/runbooks/how-to.md](../../../../../docs/runbooks/how-to.md)
   ("Serve a local mock backend to drive availability-driven QA").
2. **Natural backend churn (observational)**: VPN Gate-backed content churns frequently; SUB-02
   QA observed a favorited country disappearing live mid-session. Favorite a country that looks
   volatile (few servers, low uptime) and keep the app foregrounded; each SSE `servers-changed`
   sync re-filters the pinned section. Budget waiting time and record the sync timestamps from
   logcat.
3. **Foreground re-sync as the trigger mechanism**: background the app (HOME) and re-open it —
   SSE `onOpen` fires a `forceRefresh` sync on every foreground return; an airplane-mode toggle
   while foregrounded forces an SSE reconnect with the same effect. Use these to force a fresh
   fetch immediately after a known backend content change (options 1/2) rather than waiting.
4. **Periodic refresh**: `ServerRefreshWorker` re-syncs on its own schedule even without SSE;
   only practical when combined with a known content change and a long observation window.

If NO content-change trigger is achievable in-session (no controllable backend and no observed
churn), mark the case BLOCKED with the attempts documented — do not pass it vacuously.

## Steps

1. On the countries screen, confirm the favorited country is shown in the pinned Favorites
   section. Record `favorites_prefs.xml` (baseline) and a uiautomator dump (baseline UI).
2. Induce a sync in which the favorited country is absent (trigger options above). Confirm the
   sync actually ran via logcat (sync/SSE lines) and/or visible list change.
3. Assert the HIDE half:
   - the country's row is gone from the pinned Favorites section;
   - if it was the only available favorite, the "Favorites" header itself is absent (this
     mid-state is direct evidence for AC4);
   - `favorites_prefs.xml` is byte-identical to the baseline — the favorite was NOT deleted;
   - no crash; the list remains usable.
4. Induce a later sync in which the country is present again (restore backend data / wait for
   churn to restore it / force a foreground re-sync after restoration).
5. Assert the RESTORE half:
   - the country reappears in the pinned Favorites section automatically, with NO user action
     (no long-press, no re-favoriting performed at any point in this case);
   - `favorites_prefs.xml` is still byte-identical to the baseline;
   - long-press on the restored pinned row still shows "Remove from favorites" (state intact).
6. Server-level variant (same mechanics, run when a controllable backend makes it practical):
   with a favorited server in the target country, induce a sync where that server is absent from
   the country's server list, assert its pinned row hides while `favorite_server_ids` is
   unchanged, then restore and assert automatic reappearance. If only the country-level variant
   is executable in-session, record the server-level variant explicitly as not-run with reason.

## Expected

Hide and restore both happen automatically and only as a function of list availability; storage
(`favorites_prefs.xml`) never changes throughout; the pinned header disappears entirely while no
favorite is available and returns with the favorite; no crash/ANR in logcat.

## Cleanup

Restore any backend data modified for the test. Restore `favorites_prefs.xml` to its recorded
pre-suite state via UI toggles.
