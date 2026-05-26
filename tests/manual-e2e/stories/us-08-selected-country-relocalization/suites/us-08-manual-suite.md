---
id: US-08-MANUAL-SUITE
title: US-08 manual QA suite
purpose: Validate selected-country relocalization behavior on language switch
---

## Cases
1. US-08-MQ-01 - EN -> RU relocalization for DEFAULT_V2 - `../cases/us-08-mq-01-english-to-russian-relocalization.md`
2. US-08-MQ-02 - RU -> EN relocalization for DEFAULT_V2 - `../cases/us-08-mq-02-russian-to-english-relocalization.md`
3. US-08-MQ-03 - cache warm language switch, no mixed labels - `../cases/us-08-mq-03-cache-warm-no-mixed-language.md`
4. US-08-MQ-04 - preserve selected index when server exists - `../cases/us-08-mq-04-preserve-index-when-server-exists.md`
5. US-08-MQ-05 - safe fallback when selected server disappears - `../cases/us-08-mq-05-safe-fallback-when-server-disappears.md`
6. US-08-MQ-06 - non-v2 regression (no relocalization path) - `../cases/us-08-mq-06-non-v2-no-relocalization.md`

## Execution Plan
Execute sequentially on one mobile target to preserve stateful selection behavior.

## Observed Outcome
- US-08-MQ-01: FAIL
- US-08-MQ-02: FAIL
- US-08-MQ-03: FAIL
- US-08-MQ-04: PASS
- US-08-MQ-05: FAIL (not reproducible after AC1 path failure)
- US-08-MQ-06: PASS (spot-check)

## Follow-up Validation Outcome
- The outcomes above capture the initial pre-fix manual run.
- Defect MQ-US-08-CRITICAL-001 was later fixed and closed in flow status after follow-up verification evidence.
- Source of truth for current gate state and closure evidence: `.sdlc/status.json` flow `dev::US-08`.