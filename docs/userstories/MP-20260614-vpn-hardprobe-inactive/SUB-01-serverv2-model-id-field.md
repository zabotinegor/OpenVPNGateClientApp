---
id: SUB-01
title: "Android ServerV2 model — expose server id field"
masterPlanId: MP-20260614-vpn-hardprobe-inactive
dependsOn: []
---

# SUB-01: Android ServerV2 model — expose server id field

## Scope

Add an `id: Int` field to the Android `ServerV2` data class and propagate it through the repository
and cache layer so that the server's integer identity is available at the point of VPN connection
and failure handling.

**Assumption:** The server team will deliver the `id` field in `VpnServerV2ListItemDto`
(tracked in server repo story `US-12-server-list-expose-id.md`) before this sub-plan is activated.
This sub-plan implements the Android-side change once that dependency is met.

## Acceptance Criteria

1. `ServerV2.kt` contains a new `id: Int` field (default `0` for backwards-compatible cache reads).
2. The `id` field is included in the Gson serialisation / deserialisation of the server list cache.
3. `ServerV2.toLegacyServer()` preserves `id` or stores it in a way accessible to callers (e.g., a
   companion store or passed through to `MainSelectionInteractor`).
4. Unit tests confirm that a server list JSON response containing `"id"` is correctly parsed into
   `ServerV2.id`, and that missing `"id"` (older cache) defaults to `0` without crash.
5. Unit tests confirm that existing cache migration paths in `ServersV2Repository` are unaffected.
6. The build passes (`assembleDebugApp` + `testDebugUnitTestApp`).

## Out of scope

- Server-side DTO changes (tracked in server repo).
- Any UI display of the server ID.
- The hardprobe queue or trigger wiring (SUB-02, SUB-03, SUB-04).

## dependsOn note

No Android sub-plan dependency. External dependency: server team must expose `id` in
`GET /api/v2/servers` response before this sub-plan can be fully validated end-to-end.
