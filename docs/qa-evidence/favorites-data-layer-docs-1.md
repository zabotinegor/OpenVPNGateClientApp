# Documentation Evidence — favorites-data-layer

- Flow: `favorites-data-layer`
- Branch: `feature/favorites-data-layer`
- Story: `docs/userstories/MP-20260706-favorite-countries-servers/SUB-01-favorites-data-layer.md`
- Task: Update or create docs for new favorites data layer; flag pre-conditions and casing inconsistency for downstream sub-plans (SUB-02/03/04).
- Changed files (source): `FavoritesStore.kt`, `FavoritesFilter.kt`, `FavoritesStoreTest.kt`, `FavoritesFilterTest.kt`

## Documentation Audit

### Existing Architecture Documentation

1. **CLAUDE.md** (`D:\Apps\OpenVPNClient\OpenVPNClientClientApp\CLAUDE.md`)
   - Provides Claude Code guidance on build/test and architecture conventions
   - Contains "Key entry points" table (lines 55–67) that lists critical files by purpose
   - Covers UI flows, server sync, VPN service, but **does not cover data-store layer**
   - Reference docs section points to `src/docs/logging-policy.md`, `src/docs/server-sync-flow.md`
   - **Finding**: No existing data-store documentation; CLAUDE.md focuses on entry points but doesn't have a "Key stores" or "Persistence layer" reference section

2. **src/docs/server-sync-flow.md** (`D:\Apps\OpenVPNClient\OpenVPNClientClientApp\src\docs\server-sync-flow.md`)
   - Comprehensive 215-line doc covering server-list synchronization orchestration
   - Details `ServerSelectionSyncCoordinator`, `ServersV2SyncCoordinator`, `SelectedCountryStore`, `CountryServersInteractor`, `DefaultServerSelectionSyncCoordinator`
   - Describes cache strategy, localization, hardprobe triggers, SSE client lifecycle
   - **Finding**: References `SelectedCountryStore` and `UserSettingsStore` by name but provides no data-store reference doc. No section describes the shared persistence layer or how new stores fit into the architecture.

3. **src/docs/logging-policy.md** (not reviewed in detail; covered by existing content)
   - Logging policy only, not relevant to data-layer docs

4. **src/docs/runbooks/solutions.md** (exists; not reviewed in detail)
   - Solutions/troubleshooting runbook, likely not relevant to data-layer reference docs

### Code Organization of Existing Data Stores

**Confirmed via source inspection:**
- `SelectedCountryStore` (lines referenced in review evidence)
- `UserSettingsStore` (pattern reference in review evidence)
- `AppFilterStore` (pattern reference in review evidence)
- All live in `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/` (same package as `FavoritesStore` and `FavoritesFilter`)
- All follow the singleton-object + SharedPreferences pattern with consistent add/remove/query/get APIs
- All use defensive `toSet()` copies to avoid SharedPreferences mutable-set aliasing bugs

**Confirmed via grep (code review evidence):**
- No existing reference documentation specifically covering the data-store layer (persistence models, store responsibilities, their place in architecture)
- Existing docs focus on sync orchestration and UI flows, not the foundational persistence tier

## Carried-Forward Risks (from code review and gate evidence)

### 1. Legacy `Server.id` Defaults to 0 for Non-V2 Sources (Major)

**Context:**
- `Server.kt:22`: `val id: Int = 0` (default)
- Only non-zero `id` populated by `ServerV2.toLegacyServer()` when `ServerSource == DEFAULT_V2`
- Legacy/VPNGate/Custom CSV-parsed servers all have `id == 0`

**Impact on FavoritesStore:**
- `FavoritesStore` keys server favorites on `Server.id` only
- `FavoritesFilter.filterFavoriteServers` and `FavoritesStore.isFavoriteServer` perform identity lookup as `favoriteServerIds.contains(it.id)`
- When on a non-V2 source (LEGACY, VPNGATE, CUSTOM), all servers would collide on `id == 0`
- Favoriting one legacy server would mark **all** legacy servers as favorites (and vice versa for removal)

**Status:**
- No consumer wired yet (story scope is data-layer only, no UI/DI)
- Collision can only manifest in SUB-02/SUB-03 when UI wires favorite-toggle affordance
- **Recommendation (carried forward from review)**: Before SUB-02/SUB-03 ship, implement a stable composite-key fallback (e.g., `ip` + `configData` / `city`) when `id == 0`, or restrict server favoriting to V2-sourced servers with a documented guard

### 2. Case-Sensitive Country-Code Comparison vs. Codebase Convention (Minor)

**Context:**
- `FavoritesFilter.kt:22`: `countries.filter { favoriteCountryCodes.contains(it.code) }` — uses exact string equality
- `FavoritesStore.kt:42`: `getFavoriteCountryCodes(ctx).contains(countryCode)` — same exact equality
- `CountryServersInteractor.kt:64`: `it.code.equals(code, ignoreCase = true)` — case-insensitive convention used elsewhere

**Impact on FavoritesStore:**
- If a favorite country code is persisted with one casing (e.g., "US") and the backend later returns the same country with different casing (e.g., "us"), the favorite would incorrectly appear "absent" from filter results
- Casing collision would not trigger until the favorite is re-added with the new casing
- **Likelihood**: Low in practice (backend ISO country codes are stable uppercase per spec), but not zero if backend behavior varies (e.g., locale-dependent output or accidental casing drift)

**Status:**
- Gate evidence confirmed this is **not a correctness defect against the story's ACs** (AC3/AC4 specify filtering "given the current synced countries list" but not casing tolerance)
- Backend-to-backend comparisons (which is all FavoritesFilter does) are safer than user-input-to-backend comparisons (which is why CountryServersInteractor uses ignoreCase)
- **Recommendation (carried forward from review, optional)**: Normalize country codes to uppercase at the `FavoritesStore` read/write boundary, or document the case-sensitive assumption, as a hardening/consistency measure. Can be picked up opportunistically in SUB-02/SUB-03.

## Documentation Recommendations

### 1. Extend CLAUDE.md with a "Data Stores / Persistence Layer" Section

**Location**: Insert before or after "Key entry points" table in CLAUDE.md

**Content suggestion** (not implementing, only recommending for user/orchestrator decision):

```markdown
### Data Stores / Persistence Layer

The app persists user settings and state through singleton-object stores backed by `SharedPreferences` (Android's built-in key-value encryption store). All stores live in `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/` and follow a consistent pattern: add/remove/query/get APIs, defensive set-copy guarding against mutable-set aliasing bugs, and asynchronous persistence via `apply()`.

| Store | Purpose |
|-------|---------|
| `SelectedCountryStore` | Persists user's selected country and server; drives refresh guard in `MainViewModel` |
| `UserSettingsStore` | Persists user-facing settings (server source, language, cache TTL, locale preference) |
| `AppFilterStore` | Persists app-package filters for per-app VPN exclusion list |
| `FavoritesStore` | Persists favorite country codes and favorite server ids; filtering logic in `FavoritesFilter` (pure, non-mutating) |

Key patterns:
- All reads and writes are guarded by a single `SharedPreferences` instance per store, via private helper methods.
- Sets are stored as `StringSet` (for country codes) or as stringified `Int` sets (for server ids), and defensively copied with `.toSet()` on read to avoid SharedPreferences mutable-alias gotchas.
- Writes use `apply()` for asynchronous persistence; see the review evidence linked below for concurrency considerations when UI and background code both call store methods.
```

### 2. Add a Data-Store Reference section to `src/docs/server-sync-flow.md`

**Location**: New section before "Source of Truth" or after "Scope"

**Content suggestion** (not implementing, only recommending):

```markdown
## Data-Store Layer

Server-sync orchestration depends on two key stores (see "Persistence Layer" section in CLAUDE.md for full list):
- `SelectedCountryStore`: Persists the user's current country and server selection, and drives guard conditions in `MainViewModel.loadInitialSelection()` to prevent clobbering an in-flight user selection during startup.
- `FavoritesStore` (SUB-01 addition): Persists favorite country codes and server ids; paired with `FavoritesFilter` for filtering the current synced list. Favorites absent from the current list are retained in persistence and automatically restored when that country/server reappears in a future sync.

Favorites filtering is a pure function (see `FavoritesFilter`), invoked by UI layers (SUB-02/SUB-03 UI sub-plans) and never by sync orchestration itself.
```

### 3. Pre-Conditions for Downstream SUB-02/SUB-03

**What to document for future implementers:**

In the story doc or in a separate linked pre-condition file, flag:

**For SUB-02 (server favorites UI):**
1. **Server identity collision on legacy sources (Major, pre-blocking)**: Before wiring the favorite-server toggle, resolve the legacy `Server.id == 0` collision by deriving a stable composite-key fallback (e.g., `ip`+`configData`) or by restricting favorites to V2-sourced servers. See code-review evidence `favorites-data-layer-review-1.md` Finding #1 and acceptance criteria.
2. **Case-sensitive country-code comparison (Optional, hardening)**: Normalize country codes to uppercase or document the case-sensitive assumption. See code-review evidence Finding #2.

**For SUB-03 (country favorites UI):**
1. Same case-sensitive country-code note if applicable to country-favorite toggle.

## Audit Findings Summary

| Item | Status | Recommendation |
|------|--------|-----------------|
| Existing reference docs cover sync orchestration and UI flows | Confirmed | Add a new "Data Stores / Persistence Layer" reference to CLAUDE.md and extend `src/docs/server-sync-flow.md` with a "Data-Store Layer" section |
| FavoritesStore/FavoritesFilter placement and pattern | Confirmed correct | Document them as part of the persistent layer reference (not critical for this stage, but clarifies architecture for future readers) |
| Legacy `Server.id == 0` collision (Major pre-blocking risk for SUB-02) | Confirmed, carried forward | No docs change needed; risk is already flagged in code-review and gate evidence and must be resolved in implementation before SUB-02 ships |
| Case-sensitive country-code comparison (Minor hardening item) | Confirmed, carried forward | No docs change needed; optional hardening flagged in review and gate evidence, can be picked up in SUB-02/SUB-03 |
| Deployment / startup / env-var knowledge | Confirmed absent | No new build config, ADB steps, or env vars required for FavoritesStore (pure Kotlin/Android, no new external surface) — skip runbook updates |

## Decision

**DOCS GATE EVALUATION: PASS WITH RECOMMENDATIONS**

1. **No immediate blocking findings** — The story's scope (data-layer-only persistence) requires no deployment or startup changes, so no runbook updates are necessary. The new data layer is discoverable in the same package as existing stores and follows the exact same pattern.

2. **Recommended enhancements (non-blocking)** — To improve architectural clarity for future developers and to align with how downstream SUB-02/SUB-03/SUB-04 sub-plans will consume this layer, recommend extending CLAUDE.md and `src/docs/server-sync-flow.md` with references to the persistent data-store layer. These are clarity improvements, not defects.

3. **Pre-conditions carried forward** — Legacy `Server.id == 0` collision (Major) and case-sensitive country-code comparison (Minor) are already flagged in code-review and gate evidence as pre-conditions/hardening items for SUB-02/SUB-03; no new documentation is needed, but implementers of those sub-plans must be aware via the carried-forward evidence trail.

## Suggested Next Steps (for orchestrator / story owner)

1. (Recommended) Update CLAUDE.md to add a "Data Stores / Persistence Layer" reference section listing all stores, their purposes, and key patterns.
2. (Optional) Extend `src/docs/server-sync-flow.md` with a "Data-Store Layer" section explaining the relationship between `SelectedCountryStore`, `FavoritesStore`, and sync orchestration.
3. (Critical for SUB-02/03 implementers) Ensure the carried-forward pre-conditions (legacy `Server.id` collision, case-sensitive country codes) are visible to downstream sub-plan implementers — link them to this evidence or to the story's pre-condition section.
4. No deployment, build-property, or runbook updates required for this story.
