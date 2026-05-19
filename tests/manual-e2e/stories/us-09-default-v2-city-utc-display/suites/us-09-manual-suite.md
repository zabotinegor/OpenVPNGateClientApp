---
id: US-09-MANUAL-SUITE
title: US-09 manual QA suite
purpose: Regression
---

## Cases
1. US-09-MQ-01 - Main details show Server as selected position (current/total) - `../cases/us-09-mq-01-default-v2-list-city-utc-and-fallbacks.md`
2. US-09-MQ-02 - Main details show Address as selected server IP - `../cases/us-09-mq-02-default-v2-selected-and-main-city-utc.md`
3. US-09-MQ-03 - Reconnect and reopen preserve Server/Address details contract - `../cases/us-09-mq-03-default-v2-reconnect-reopen-persistence.md`
4. US-09-MQ-04 - Source switch parity keeps Server/Address contract for non-v2 and custom - `../cases/us-09-mq-04-non-v2-source-switch-regression.md`

## Execution Plan
Run the cases sequentially on one Android phone so selection and persistence state can be observed across the whole flow.

## Latest Execution Result
- Date: 2026-05-19
- Device: Android phone (ADB-connected Mi 9 SE)
- Outcome: SUPERSEDED (contract updated, rerun required)
- Case status:
	- US-09-MQ-01: SUPERSEDED
	- US-09-MQ-02: SUPERSEDED
	- US-09-MQ-03: SUPERSEDED
	- US-09-MQ-04: SUPERSEDED
- Evidence root: artifacts/manual-qa/2026-05-19-us09-manual-qa
- Note: this execution used the previous city/UTC expectation set and is kept only as historical evidence.
