# Known Issues and Solutions

This runbook collects non-obvious problems and their solutions discovered during development and
QA. Add an entry only when the issue is likely to recur and the fix is not obvious from the
error message alone.

## Index

Read this list first and jump to the one relevant heading — do not read the whole file.

- [SseServerEventsClientTest onOpen-reset flake (86cb9kpx9)](#sseservereventslienttest-onopen-reset-flake-86cb9kpx9)

---

## SseServerEventsClientTest onOpen-reset flake (86cb9kpx9)

**Status: RESOLVED** — pure JVM test flakiness fix, no production code changed

**Files changed:** `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClientTest.kt`

### Symptoms

- Test "onOpen does not reset failure count" fails intermittently only during full-suite runs with JVM contention
- Test never fails when run in isolation
- No product behavior changed — test-only issue

### Root cause

The test held a counter value (`failuresOnCurrentUrl`) that was genuinely transient under Dispatchers.IO
contention. The assertion raced against a delayed coroutine dispatch and a value reset that could
fire between the assertion's poll and the actual read, causing the test to observe an already-reset
counter instead of the stable pre-reset value it expected. Timing-sensitive assertions on mutable
state race against scheduled work.

### Fix applied

Split the test into two scenarios with different connection stability thresholds:

1. **Unstable connection path (new test):** connection that triggers reconnect fast enough that
   `failuresOnCurrentUrl` never gets reset before the poll reads it. Uses `stableConnectionResetDelayMs=5000ms`
   (stable threshold) with an unstable second connection, creating a window where `failuresOnCurrentUrl`
   is genuinely stable (not pending a transient reset).

2. **Stable connection path:** separate test affirming that a truly stable connection does reset the
   failure counter.

This ensures the assertion reads a value that stays genuinely stable under any JVM contention level,
rather than racing a transient window.

### Lessons learned

- Do not assert on mutable counters that are scheduled to change; instead, structure the test so
  state transitions happen *between* assertion checkpoints.
- Under high JVM load (full-suite runs), tests that race transient windows become reliably flaky.
  Isolation masks the race. Code review and mutation testing catch races that timing does not.

### Follow-up (not fixed in this task)

A second pre-existing flake exists in the same test class (`stable connection triggers quick reconnect`).
Its root cause is an under-injected clock: the test hardcodes `delay(500)` but the production code
reads `SystemClock.elapsedRealtime()`, causing timing assertions to fail under JVM contention. This
requires injecting a controllable clock into `SseServerEventsClient` — a larger refactor beyond the
scope of the current flake fix. Tracked as a separate tech-debt item.
