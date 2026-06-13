---
id: SUB-03
title: "Hardprobe Retrofit API client"
masterPlanId: MP-20260614-vpn-hardprobe-inactive
dependsOn: []
---

# SUB-03: Hardprobe Retrofit API client

## Scope

Create the Retrofit interface and service layer for calling the server-side hardprobe endpoint
(`POST /api/v2/servers/{id}/probe`) and wire it into the existing `CoreDi` dependency graph.

## Acceptance Criteria

1. A `ServersProbeApi` Retrofit interface exists in `src/core` with a `@POST` method targeting
   `api/v2/servers/{id}/probe`, returning a `Response<Unit>` (or equivalent).
2. The interface is instantiated against the primary server base URL using the existing OkHttp
   client from `CoreDi` (same instance as `ServersV2Api`).
3. A `HardProbeApiClient` (or equivalent) wraps the Retrofit call and maps HTTP responses to a
   sealed result type covering: `Queued` (202), `NotFound` (404), `NoConfigData` (422),
   `RateLimited` (429), `ServiceUnavailable` (503), `Error`.
4. The client and Retrofit interface are registered in `CoreDi.kt` via Koin.
5. Unit tests cover all mapped result cases using a mock HTTP server or OkHttp `MockWebServer`.
6. The build passes (`assembleDebugApp` + `testDebugUnitTestApp`).

## Out of scope

- WorkManager queue or retry logic (SUB-02).
- VPN failure detection or trigger wiring (SUB-04).
- Server-side endpoint changes.

## dependsOn note

No dependency on other Android sub-plans. Assumes the server endpoint
`POST /api/v2/servers/{id}/probe` is reachable at the configured primary URL.
