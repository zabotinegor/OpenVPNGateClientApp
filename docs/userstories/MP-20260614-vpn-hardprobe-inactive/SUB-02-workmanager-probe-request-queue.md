---
id: SUB-02
title: "Durable WorkManager probe request queue"
masterPlanId: MP-20260614-vpn-hardprobe-inactive
dependsOn: []
---

# SUB-02: Durable WorkManager probe request queue

## Scope

Implement a durable, WorkManager-backed HTTP request queue in `src/core` that accepts enqueue
requests for `POST /api/v2/servers/{id}/probe` and executes them reliably — surviving app restarts,
respecting server-side rate limiting (HTTP 429), and deduplicating concurrent requests for the
same server.

## Acceptance Criteria

1. A `ProbeRequestQueue` (or equivalent name) interface exists in `src/core` with a single
   `enqueue(serverId: Int)` method.
2. A `ProbeRequestWorker` (WorkManager `CoroutineWorker`) issues `POST /api/v2/servers/{id}/probe`
   using the existing OkHttp/Retrofit infrastructure from `CoreDi`.
3. On HTTP 202 the worker returns `Result.success()`.
4. On HTTP 429 the worker returns `Result.retry()` with exponential backoff so the system
   respects server-side rate limits.
5. On HTTP 404 or 422 the worker returns `Result.failure()` without retry (non-retryable errors).
6. WorkManager uniqueness policy prevents duplicate in-flight requests for the same `serverId`
   (use `ExistingWorkPolicy.KEEP` or equivalent).
7. The queue and worker are wired into Koin via `CoreDi.kt`.
8. Unit tests cover: success path, 429 retry, 404/422 failure, and deduplication behaviour.
9. The build passes (`assembleDebugApp` + `testDebugUnitTestApp`).

## Out of scope

- Any VPN failure detection or trigger logic (SUB-04).
- Changes to `ServerV2` model (SUB-01).
- UI for probe status.

## dependsOn note

No dependency on other sub-plans.
