# SUB05-CORE: Manual QA Suite — Fix Broken Instrumented Tests

## Story
SUB-05: Fix broken instrumented tests (MainActivitySmokeTest)
Branch: fix/sub-05-instrumented-tests
Devices: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY), Xiaomi Mi 9T Pro Android 11 (b6e8f6bd)

---

## Execution Order
1. Phase A — Build validation (no device required):
   - MQ-SUB05-005 (unit tests + debug build)
   - MQ-SUB05-004 (code review — no timing hacks)
2. Phase B — Instrumented test execution:
   - MQ-SUB05-001 (Samsung A71 fresh install)
   - MQ-SUB05-002 (Xiaomi Mi 9T Pro)
3. Phase C — Manual regression smoke:
   - MQ-SUB05-003 (launch + navigation on both devices)

## Preconditions
- Source at `fix/sub-05-instrumented-tests` HEAD
- Both devices connected via ADB
- Submodules initialized

## Exit Criteria
- MQ-SUB05-005: PASS (build + unit tests green)
- MQ-SUB05-004: PASS (no timing hacks)
- MQ-SUB05-001: PASS (7/7 instrumented tests on Samsung)
- MQ-SUB05-002: PASS (7/7 instrumented tests on Xiaomi)
- MQ-SUB05-003: PASS (manual smoke — no regression)
- Any FAIL blocks story completion

---

## Run 1 — QA Execution (2026-06-17)

| Case | Title | Device | Result |
|------|-------|--------|--------|
| MQ-SUB05-005 | Unit tests and debug build | host | PENDING |
| MQ-SUB05-004 | No timing hacks in code | host | PENDING |
| MQ-SUB05-001 | Instrumented tests Samsung A71 | R58N849XQEY | PENDING |
| MQ-SUB05-002 | Instrumented tests Xiaomi Mi 9T Pro | b6e8f6bd | PENDING |
| MQ-SUB05-003 | Manual smoke regression | both | PENDING |

**Overall: PENDING**
