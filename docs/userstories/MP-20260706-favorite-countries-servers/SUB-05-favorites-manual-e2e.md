# SUB-05: Manual E2E coverage for favorites (phone + TV)

## Scope boundary
Extend the manual E2E test suite (`tests/manual-e2e/`) with scenarios covering favorite countries and favorite servers end to end on both a phone device and a TV device, including the availability hide/restore behavior.

## Acceptance criteria
1. New manual E2E specs/cases exist under `tests/manual-e2e/` covering: add favorite country, remove favorite country, add favorite server, remove favorite server, on phone.
2. New manual E2E specs/cases cover the same add/remove flows via D-pad long-press on TV.
3. A scenario verifies that a favorited country/server disappears from the pinned favorites section when absent from a sync, and reappears automatically once present again in a later sync (no manual re-favoriting required).
4. A scenario verifies the pinned favorites section is absent when there are no currently-available favorites.
5. All new manual E2E cases follow the existing suite structure documented in `tests/manual-e2e/README.md` and are actually executed against a real phone and a real TV device (or TV emulator), with pass/fail evidence recorded.

## Out of scope
- Automated instrumented (Espresso) tests — covered as implementation-detail unit/instrumentation tests within SUB-01/SUB-02/SUB-03/SUB-04 themselves, not here.
- Any further product behavior changes; this sub-plan is verification-only.

## dependsOn
SUB-04
