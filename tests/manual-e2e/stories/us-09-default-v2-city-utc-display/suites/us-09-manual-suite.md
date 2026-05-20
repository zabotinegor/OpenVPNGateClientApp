---
id: US-09-MANUAL-SUITE
title: US-09 manual QA suite - DEFAULT_V2 city/UTC display
purpose: Regression & Feature Validation
---

## Cases
1. US-09-MQ-01 - DEFAULT_V2 server list shows city/UTC on cards (2-line), city-only (1-line), or IP fallback - `../cases/us-09-mq-01-default-v2-list-city-utc-and-fallbacks.md`
2. US-09-MQ-02 - Main screen shows "City" label with city/UTC format (or fallbacks) for DEFAULT_V2 - `../cases/us-09-mq-02-default-v2-selected-and-main-city-utc.md`
3. US-09-MQ-03 - City/UTC display persists after app reopen and state restoration - `../cases/us-09-mq-03-default-v2-reconnect-reopen-persistence.md`
4. US-09-MQ-04 - Source switch shows correct label/value: "City" for DEFAULT_V2, "Address" for other sources - `../cases/us-09-mq-04-non-v2-source-switch-regression.md`

## Execution Plan
Run the cases sequentially on one Android phone so selection, persistence, and source-switching state can be observed across the whole flow.

## Latest Execution Plan
- Date: 2026-05-20 (UPDATED SPECIFICATION)
- Device: Android phone (ADB-connected Mi 9 SE or equivalent)
- Expected outcome: FULL RERUN WITH NEW SPEC
- Case status (expected):
	- US-09-MQ-01: Not yet executed (new spec)
	- US-09-MQ-02: Not yet executed (new spec)
	- US-09-MQ-03: Not yet executed (new spec)
	- US-09-MQ-04: Not yet executed (new spec)
- Evidence root: artifacts/manual-qa/2026-05-20-us09-manual-qa-rerun-updated-spec (to be created during execution)
- Note: Specification updated to clarify city/UTC display requirements for DEFAULT_V2 source only (2026-05-20)
