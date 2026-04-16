---
name: update-engine
description: "Update the external OpenVPN engine fork and keep this app integration branch aligned with minimal conflicts and validated builds."
argument-hint: "Example: sync upstream ics-openvpn to engine main and merge into OpenVPNClientApp-integration"
user-invocable: true
---

# Update Engine Skill

This skill defines a strict workflow for updating the OpenVPN engine used by this app.

## When to use
- Syncing `schwabe/ics-openvpn` updates into `zabotinegor/OpenVPNGateClientEngine`.
- Updating `OpenVPNClientApp-integration` branch and validating client compatibility.
- Preparing a repeatable and auditable engine refresh process.

## Mandatory clarifications
Ask the user before execution when any of these are not explicit:
- Engine repository local path (if not already available in local environment).
- Upstream remote name and branch (default: `upstream/main`).
- Fork remote name and branch targets (default: `origin/main` and `origin/OpenVPNClientApp-integration`).
- Whether to run quick validation (`assembleDebugApp`) or full validation (`assembleDebugApp` + `testDebugUnitTestApp`).

Default only when the user clearly allows defaults:
- Upstream branch: `main`
- Integration branch: `OpenVPNClientApp-integration`
- Validation depth: `full`

## Strict procedure
1. Read `AGENTS.md` and confirm engine update constraints.
2. Verify submodule mapping and branch intent from `.gitmodules` and `src/settings.gradle.kts`.
3. In engine repository:
   - Fetch all remotes.
   - Checkout/update fork `main` from upstream.
   - Merge or rebase upstream `main` into fork `main` as requested.
4. In engine repository:
   - Checkout `OpenVPNClientApp-integration`.
   - Merge updated `main` into `OpenVPNClientApp-integration`.
   - Resolve conflicts minimally while preserving library integration behavior.
5. In client repository:
   - Update submodule pointer if engine commit changed.
   - Ensure submodules are initialized (`git submodule update --init --recursive`).
6. Validate client from `src/`:
   - `./gradlew assembleDebugApp`
   - `./gradlew testDebugUnitTestApp`
   - Run release task only when requested and required properties are available.
7. Update branches and push:
   - Push engine branches.
   - Push client branch with updated submodule pointer/docs changes.
8. Update markdown docs if workflow, constraints, or required checks changed.
9. Return structured results using the result format below.

## Conflict resolution policy
- Keep engine changes minimal and targeted.
- Preserve `:openVpnEngine -> external/OpenVPNEngine/main` wiring.
- Do not alter release packaging defaults unless explicitly requested.
- Do not rewrite unrelated engine modules during merge cleanup.

## Result format
- Inputs and assumptions
- Engine sync commands and result
- Integration merge commands and result
- Conflict list and resolutions
- Client validation commands and result
- Branch push status
- Documentation changes
- Next action if blocked

## References
- [Engine update acceptance checklist](./references/engine-update-checklist.md)
