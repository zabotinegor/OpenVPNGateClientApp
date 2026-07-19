# US-14 — Update OpenVPN Engine from Upstream ics-openvpn

## User Story

As the app maintainer, I want the OpenVPN engine fork synchronized with upstream
`schwabe/ics-openvpn` and the client validated against it, so that the app ships with
current engine fixes (including the 2026-07-12 hardware-key API update) without regressions.

## Background

- Engine fork: `zabotinegor/OpenVPNGateClientEngine`, local checkout path recorded in
  `AGENTS.local.md` (machine-local path; see `AGENTS.local.md` conventions).
- Fork `master` is 10 commits behind `upstream/master` (latest upstream commit `ede0aa0b`,
  2026-07-12, "Use modern API to query location of hardware key location") and 2 fork-local
  commits ahead of upstream.
- `OpenVPNClientApp-integration` already contains current fork master; the client submodule
  pointer (`a83da9ff`) is at the integration tip.
- The full delta is the 10 upstream commits flowing: upstream/master → fork master →
  `OpenVPNClientApp-integration` → client submodule bump.
- Branch names in the engine repo are `master` (not `main`).
- Approved decisions: merge (not rebase) upstream into fork master; full validation;
  client branch `feature/us-14-update-openvpn-engine` renamed from `chore/agent-sync-experiment`
  (carries one already-pushed agent-sync commit `4328b54` — accepted).

## Acceptance Criteria

### AC-1 — Fork master synced with upstream
**Given** the engine repo with remotes `upstream` (schwabe/ics-openvpn) and `origin` (fork),
**When** `upstream/master` is merged into fork `master` (merge commit, no rebase),
**Then** fork master contains all 10 upstream commits, retains both fork-local commits,
and is pushed to `origin/master`.

### AC-2 — Integration branch updated
**Given** the updated fork master,
**When** it is merged into `OpenVPNClientApp-integration` with minimal conflict resolution
preserving the engine-as-library shape,
**Then** the integration branch is pushed and remains consumable by the client as a library.

### AC-3 — Client submodule bump validated
**Given** the updated integration branch,
**When** the submodule pointer at `src/external/OpenVPNEngine` is bumped on branch
`feature/us-14-update-openvpn-engine`,
**Then** `./gradlew assembleDebugApp` and `./gradlew testDebugUnitTestApp` pass from `src/`.

### AC-4 — Wiring and packaging unchanged
**Given** the completed update,
**Then** `:openVpnEngine → src/external/OpenVPNEngine/main` wiring, the `.gitmodules`
branch declaration (`OpenVPNClientApp-integration`), release hardening flags, and
`jniLibs.useLegacyPackaging` settings are unchanged.

## Out of Scope

- Engine refactors beyond minimal conflict resolution.
- Release builds or publishing.
- Media submodule updates.
- Changes to VPN client business logic in `src/core`, `src/mobile`, `src/tv`.

## Risks

- Upstream native/build-script changes could break the client build — mitigated by full validation.
- Merge conflicts in engine Gradle/build files must be resolved minimally without incidental refactors.
- The renamed branch carries the already-pushed agent-sync commit `4328b54` into the PR — accepted.

## Implementation Notes

- Follow `.github/skills/update-engine/SKILL.md` and the "OpenVPN Engine Update Workflow" section of `AGENTS.md`.
- Engine repo work happens in the standalone checkout; the client submodule then fetches the pushed result.
- `git submodule update --init --recursive` before validation builds.

## Test Scenarios

1. Debug build of mobile + tv APKs (`assembleDebugApp`).
2. Full unit test suite (`testDebugUnitTestApp`).
3. Manual QA smoke on Android device: install debug APK, connect VPN, verify traffic, disconnect.

## Definition of Done

- Engine `master` and `OpenVPNClientApp-integration` pushed with upstream changes.
- Client submodule pointer committed on `feature/us-14-update-openvpn-engine`.
- Full validation green; device smoke passed.
- Docs updated if workflow or constraints changed.
- PR merged via squash after the bot review loop.
