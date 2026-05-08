---
id: SPEC-US-01-V2-LAZY-LOADING
title: US-01 lazy loading and DEFAULT_V2 source behavior
surface: android
relatedSuite: US-01-V2-LAZY-LOADING-CORE
---

## Behavior Under Test
The app must use the DEFAULT_V2 source, load country list first, load servers only after country selection, and avoid JSON parsing crashes for wrapped v2 responses.

## Acceptance Mapping
- ME-3: Country list loads from v2 source.
- ME-4: Country selection opens server list without JSONException.
- ME-8: Server selection returns to main state with selected server.
- ME-9: Disconnect path remains functional.
- ME-10: Back navigation returns from server list to country list.
- ME-11: Re-entering the same country loads correctly (cache hit).
- ME-12: Settings show DEFAULT_V2 source selected.

## Data Contract Notes
- Countries endpoint: `/api/v2/servers/countries/active`
- Servers endpoint: `/api/v2/servers?countryCode=XX&isActive=true&skip=0&take=50`
- Wrapped response contract: `{ "items": [...], "total": N, "page": N, "pageSize": N }`

## Risks
- Endpoint contract drift can reintroduce wrapped JSON parsing failures.
- Device-specific UI timing can cause false negatives around navigation and selection checks.

## Evidence Policy
- Capture screenshots for ME-3, ME-4, ME-8, ME-10, ME-11, ME-12.
- Capture logcat after ME-4 to prove no `JSONException` from app code path.
- Store artifacts under [../../../../artifacts/manual-qa](../../../../artifacts/manual-qa) with a date/story folder.
