# Engine Update Acceptance Checklist

Use this checklist to confirm an engine refresh is complete and safe.

## Source sync
- Upstream `schwabe/ics-openvpn` branch was fetched and inspected.
- Fork `main` was synchronized with upstream changes.
- Synchronization strategy (merge/rebase) is documented in the run report.

## Integration branch
- `OpenVPNClientApp-integration` received the latest fork `main`.
- Conflict resolution was minimal and conflict files are documented.
- Library-oriented behavior remained intact.

## Client wiring safety
- `.gitmodules` branch/path assumptions still match intended integration branch.
- `src/settings.gradle.kts` still maps `:openVpnEngine` to `external/OpenVPNEngine/main`.
- No accidental packaging/build setting regressions were introduced.

## Validation
- Submodules were initialized (`git submodule update --init --recursive`).
- `assembleDebugApp` passed from `src/`.
- `testDebugUnitTestApp` passed from `src/`.
- Release validation was run when requested with required properties.

## Branch and docs hygiene
- Engine branch updates were pushed.
- Client branch with updated submodule pointer was pushed.
- Relevant markdown instructions were updated if process/constraints changed.
- Final report includes commands, results, conflicts, and follow-ups.
